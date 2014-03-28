/*
 * Copyright 2012-2014 TORCH GmbH
 *
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.graylog2.rest.resources.streams.alerts;

import com.codahale.metrics.annotation.Timed;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.mail.EmailException;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.graylog2.alerts.*;
import org.graylog2.alerts.types.DummyAlertCondition;
import org.graylog2.database.ValidationException;
import org.graylog2.indexer.Indexer;
import org.graylog2.plugin.Tools;
import org.graylog2.plugin.alarms.transports.TransportConfigurationException;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.rest.documentation.annotations.*;
import org.graylog2.rest.resources.RestResource;
import org.graylog2.rest.resources.streams.alerts.requests.CreateConditionRequest;
import org.graylog2.security.RestPermissions;
import org.graylog2.streams.StreamService;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author Lennart Koopmann <lennart@torch.sh>
 */
@RequiresAuthentication
@Api(value = "Alerts", description = "Manage stream alerts")
@Path("/streams/{streamId}/alerts")
public class StreamAlertResource extends RestResource {

    private static final Logger LOG = LoggerFactory.getLogger(StreamAlertResource.class);

    @Inject
    private StreamService streamService;

    @Inject
    private AlertService alertService;

    @Inject
    private AlertSender alertSender;

    private static final String CACHE_KEY_BASE = "alerts";

    private static final Cache<String, Map<String, Object>> cache = CacheBuilder.newBuilder()
            .expireAfterWrite(AlertImpl.REST_CHECK_CACHE_SECONDS, TimeUnit.SECONDS)
            .build();

    @Inject
    private Indexer indexer;

    @POST @Timed
    @Path("conditions")
    @ApiOperation(value = "Create a alert condition")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response create(@ApiParam(title = "streamId", description = "The stream id this new alert condition belongs to.", required = true) @PathParam("streamId") String streamid,
                           @ApiParam(title = "JSON body", required = true) String body) {
        CreateConditionRequest ccr;
        checkPermission(RestPermissions.STREAMS_EDIT, streamid);

        try {
            ccr = objectMapper.readValue(body, CreateConditionRequest.class);
        } catch(IOException e) {
            LOG.error("Error while parsing JSON", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        Stream stream;
        try {
            stream = streamService.load(streamid);
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }

        final AlertCondition alertCondition;
        try {
            alertCondition = alertService.fromRequest(ccr, stream);
        } catch (AlertCondition.NoSuchAlertConditionTypeException e) {
            LOG.error("Invalid alarm condition type.", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        try {
            streamService.addAlertCondition(stream, alertCondition);
        } catch (ValidationException e) {
            LOG.error("Validation error.", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("alert_condition_id", alertCondition.getId());

        return Response.status(Response.Status.CREATED).entity(json(result)).build();
    }

    @GET @Timed
    @ApiOperation(value = "Get the " + AlertImpl.MAX_LIST_COUNT + " most recent alarms of this stream.")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response list(@ApiParam(title = "streamId", description = "The stream id this new alert condition belongs to.", required = true) @PathParam("streamId") String streamid,
                         @ApiParam(title = "since", description = "Optional parameter to define a lower date boundary. (UNIX timestamp)", required = false) @QueryParam("since") int sinceTs) {
        checkPermission(RestPermissions.STREAMS_READ, streamid);

        Stream stream;
        try {
            stream = streamService.load(streamid);
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }

        DateTime since;
        if (sinceTs > 0) {
            since = new DateTime(sinceTs*1000L);
        } else {
            since = null;
        }

        List<Map<String,Object>> conditions = Lists.newArrayList();
        for(Alert alert : alertService.loadRecentOfStream(stream.getId(), since)) {
            conditions.add(alert.toMap());
        }

        long total = alertService.totalCount();

        Map<String, Object> result = Maps.newHashMap();
        result.put("alerts", conditions);
        result.put("total", total);

        return Response.status(Response.Status.OK).entity(json(result)).build();
    }

    @GET @Timed
    @Path("check")
    @ApiOperation(value = "Check for triggered alert conditions of this streams. Results cached for " + AlertImpl.REST_CHECK_CACHE_SECONDS + " seconds.")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response checkConditions(@ApiParam(title = "streamId", description = "The ID of the stream to check.", required = true) @PathParam("streamId") String streamid) {
        checkPermission(RestPermissions.STREAMS_READ, streamid);

        final Stream stream;
        try {
            stream = streamService.load(streamid);
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }

        Map<String, Object> result;
        try {
            result = cache.get(CACHE_KEY_BASE + stream.getId(), new Callable<Map<String, Object>>() {
                @Override
                public Map<String, Object> call() throws Exception {
                    List<Map<String, Object>> results = Lists.newArrayList();
                    int triggered = 0;
                    for (AlertCondition alertCondition : streamService.getAlertConditions(stream)) {
                        Map<String, Object> conditionResult = Maps.newHashMap();
                        conditionResult.put("condition", alertService.asMap(alertCondition));

                        AlertCondition.CheckResult checkResult = alertService.triggeredNoGrace(alertCondition, indexer);
                        conditionResult.put("triggered", checkResult.isTriggered());

                        if (checkResult.isTriggered()) {
                            triggered++;
                            conditionResult.put("alert_description", checkResult.getResultDescription());
                        }

                        results.add(conditionResult);
                    }

                    Map<String, Object> result = Maps.newHashMap();
                    result.put("results", results);
                    result.put("calculated_at", Tools.getISO8601String(Tools.iso8601()));
                    result.put("total_triggered", triggered);

                    return result;
                }
            });
        } catch (ExecutionException e) {
            LOG.error("Could not check for alerts.", e);
            throw new WebApplicationException(500);
        }

        return Response.status(Response.Status.OK).entity(json(result)).build();
    }

    @GET @Timed
    @Path("conditions")
    @ApiOperation(value = "Get all alert conditions of this stream")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response listConditions(@ApiParam(title = "streamId", description = "The stream id this new alert condition belongs to.", required = true) @PathParam("streamId") String streamid) {
        checkPermission(RestPermissions.STREAMS_READ, streamid);

        Stream stream;
        try {
            stream = streamService.load(streamid);
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }

        List<Map<String, Object>> conditions = Lists.newArrayList();
        for (AlertCondition alertCondition : streamService.getAlertConditions(stream)) {
            conditions.add(alertService.asMap(alertCondition));
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("conditions", conditions);
        result.put("total", conditions.size());

        return Response.status(Response.Status.OK).entity(json(result)).build();
    }

    @DELETE @Timed
    @Path("conditions/{conditionId}")
    @ApiOperation(value = "Delete an alert condition")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response list(@ApiParam(title = "streamId", description = "The stream id this new alert condition belongs to.", required = true) @PathParam("streamId") String streamid,
                         @ApiParam(title = "conditionId", description = "The stream id this new alert condition belongs to.", required = true) @PathParam("conditionId") String conditionId) {
        checkPermission(RestPermissions.STREAMS_READ, streamid);

        Stream stream;
        try {
            stream = streamService.load(streamid);
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }

        streamService.removeAlertCondition(stream, conditionId);

        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @POST @Timed
    @Path("receivers")
    @ApiOperation(value = "Add an alert receiver")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response addReceiver(
            @ApiParam(title = "streamId", description = "The stream id this new alert condition belongs to.", required = true) @PathParam("streamId") String streamid,
            @ApiParam(title = "entity", description = "Name/ID of user or email address to add as alert receiver.", required = true) @QueryParam("entity") String entity,
            @ApiParam(title = "type", description = "Type: users or emails", required = true) @QueryParam("type") String type
            ) {
        checkPermission(RestPermissions.STREAMS_EDIT, streamid);

        if(!type.equals("users") && !type.equals("emails")) {
            LOG.warn("No such type: [{}]", type);
            throw new WebApplicationException(400);
        }

        Stream stream;
        try {
            stream = streamService.load(streamid);
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }

        // Maybe the list already contains this receiver?
        if (stream.getAlertReceivers().containsKey(type) || stream.getAlertReceivers().get(type) != null) {
            if (stream.getAlertReceivers().get(type).contains(entity)) {
                return Response.status(Response.Status.CREATED).build();
            }
        }

        streamService.addAlertReceiver(stream, type, entity);

        return Response.status(Response.Status.CREATED).build();
    }

    @DELETE @Timed
    @Path("receivers")
    @ApiOperation(value = "Remove an alert receiver")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response removeReceiver(
            @ApiParam(title = "streamId", description = "The stream id this new alert condition belongs to.", required = true) @PathParam("streamId") String streamid,
            @ApiParam(title = "entity", description = "Name/ID of user or email address to remove from alert receivers.", required = true) @QueryParam("entity") String entity,
            @ApiParam(title = "type", description = "Type: users or emails", required = true) @QueryParam("type") String type) {
        checkPermission(RestPermissions.STREAMS_EDIT, streamid);

        if(!type.equals("users") && !type.equals("emails")) {
            LOG.warn("No such type: [{}]", type);
            throw new WebApplicationException(400);
        }

        Stream stream;
        try {
            stream = streamService.load(streamid);
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }

        streamService.removeAlertReceiver(stream, type, entity);

        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @GET @Timed
    @Path("sendDummyAlert")
    @ApiOperation(value = "Send a test mail for a given stream")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response sendDummyAlert(@ApiParam(title = "streamId",
            description = "The stream id this new alert condition belongs to.",
            required = true) @PathParam("streamId") String streamid)
            throws TransportConfigurationException, EmailException {
        checkPermission(RestPermissions.STREAMS_EDIT, streamid);

        Stream stream;
        try {
            stream = streamService.load(streamid);
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }

        Map<String, Object> parameters = Maps.newHashMap();
        DummyAlertCondition dummyAlertCondition = new DummyAlertCondition(stream, null, null, Tools.iso8601(), "admin", parameters);

        try {
            AlertCondition.CheckResult checkResult = dummyAlertCondition.runCheck(indexer);
            alertSender.sendEmails(stream,checkResult);
        } catch (TransportConfigurationException e) {
            return Response.serverError().entity("E-Mail transport is not or improperly configured.").build();
        } catch (EmailException e) {
            LOG.error("Sending dummy alert failed: {}", e);
            return Response.serverError().entity(e.getMessage()).build();
        }

        return Response.status(Response.Status.NO_CONTENT).build();
    }
}
