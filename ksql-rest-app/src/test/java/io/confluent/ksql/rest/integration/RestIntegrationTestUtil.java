/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.rest.integration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.UrlEscapers;
import io.confluent.ksql.json.JsonMapper;
import io.confluent.ksql.rest.client.BasicCredentials;
import io.confluent.ksql.rest.client.KsqlRestClient;
import io.confluent.ksql.rest.client.QueryStream;
import io.confluent.ksql.rest.client.RestResponse;
import io.confluent.ksql.rest.entity.CommandStatus;
import io.confluent.ksql.rest.entity.CommandStatus.Status;
import io.confluent.ksql.rest.entity.CommandStatusEntity;
import io.confluent.ksql.rest.entity.KsqlEntity;
import io.confluent.ksql.rest.entity.KsqlEntityList;
import io.confluent.ksql.rest.entity.KsqlErrorMessage;
import io.confluent.ksql.rest.entity.KsqlRequest;
import io.confluent.ksql.rest.entity.StreamedRow;
import io.confluent.ksql.rest.server.TestKsqlRestApp;
import io.confluent.ksql.test.util.secure.Credentials;
import io.confluent.ksql.util.TestDataProvider;
import io.confluent.rest.validation.JacksonMessageBodyProvider;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

final class RestIntegrationTestUtil {

  private RestIntegrationTestUtil() {
  }

  static List<KsqlEntity> makeKsqlRequest(final TestKsqlRestApp restApp, final String sql) {
    return makeKsqlRequest(restApp, sql, Optional.empty());
  }

  static List<KsqlEntity> makeKsqlRequest(
      final TestKsqlRestApp restApp,
      final String sql,
      final Optional<BasicCredentials> userCreds
  ) {
    try (final KsqlRestClient restClient = restApp.buildKsqlClient(userCreds)) {

      final RestResponse<KsqlEntityList> res = restClient.makeKsqlRequest(sql);

      throwOnError(res);

      return awaitResults(restClient, res.getResponse());
    }
  }

  static List<StreamedRow> makeQueryRequest(
      final TestKsqlRestApp restApp,
      final String sql,
      final Optional<BasicCredentials> userCreds
  ) {
    return makeQueryRequest(restApp, sql, userCreds, null);
  }

  static List<StreamedRow> makeQueryRequest(
      final TestKsqlRestApp restApp,
      final String sql,
      final Optional<BasicCredentials> userCreds,
      final Map<String, ?> properties
  ) {
    try (final KsqlRestClient restClient = restApp.buildKsqlClient(userCreds)) {

      final RestResponse<QueryStream> res = restClient.makeQueryRequest(sql, null, properties);

      throwOnError(res);

      final QueryStream s = res.getResponse();

      final Builder<StreamedRow> builder = ImmutableList.builder();
      while (s.hasNext()) {
        builder.add(s.next());
      }
      return builder.build();
    }
  }

  static KsqlErrorMessage makeQueryRequestWithError(
      final TestKsqlRestApp restApp,
      final String sql,
      final Optional<BasicCredentials> userCreds,
      final Map<String, ?> properties
  ) {
    try (final KsqlRestClient restClient = restApp.buildKsqlClient(userCreds)) {

      final RestResponse<QueryStream> res = restClient.makeQueryRequest(sql, null, properties);

      throwOnNoError(res);

      return res.getErrorMessage();
    }
  }

  /**
   * Make a query request using a basic Http client.
   *
   * @param restApp the test app instance to issue the request against
   * @param sql the sql payload
   * @param cmdSeqNum optional sequence number of previous command
   * @return the response payload
   */
  static String rawRestQueryRequest(
      final TestKsqlRestApp restApp,
      final String sql,
      final Optional<Long> cmdSeqNum
  ) {
    final KsqlRequest request = new KsqlRequest(
        sql,
        ImmutableMap.of(),
        cmdSeqNum.orElse(null)
    );

    final URI listener = restApp.getHttpListener();

    final Client httpClient = ClientBuilder.newBuilder()
        .register(new JacksonMessageBodyProvider(JsonMapper.INSTANCE.mapper))
        .build();

    try {
      final Response response = httpClient
          .target(listener)
          .path("/query")
          .request(MediaType.APPLICATION_JSON_TYPE)
          .post(Entity.json(request));

      return response.readEntity(String.class);
    } finally {
      httpClient.close();
    }
  }

  static void createStream(final TestKsqlRestApp restApp, final TestDataProvider<?> dataProvider) {
    makeKsqlRequest(
        restApp,
        "CREATE STREAM " + dataProvider.kstreamName()
            + " (" + dataProvider.ksqlSchemaString() + ") "
            + "WITH (kafka_topic='" + dataProvider.topicName() + "', value_format='json');"
    );
  }

  private static List<KsqlEntity> awaitResults(
      final KsqlRestClient ksqlRestClient,
      final List<KsqlEntity> pending
  ) {
    return pending.stream()
        .map(e -> awaitResult(e, ksqlRestClient))
        .collect(Collectors.toList());
  }

  private static KsqlEntity awaitResult(
      final KsqlEntity e,
      final KsqlRestClient ksqlRestClient
  ) {
    if (!(e instanceof CommandStatusEntity)) {
      return e;
    }

    CommandStatusEntity cse = (CommandStatusEntity) e;
    final String commandId = cse.getCommandId().toString();

    while (cse.getCommandStatus().getStatus() != Status.ERROR
        && cse.getCommandStatus().getStatus() != Status.SUCCESS) {

      final RestResponse<CommandStatus> res = ksqlRestClient.makeStatusRequest(commandId);

      throwOnError(res);

      cse = new CommandStatusEntity(
          cse.getStatementText(),
          cse.getCommandId(),
          res.getResponse(),
          cse.getCommandSequenceNumber()
      );
    }

    return cse;
  }

  private static void throwOnError(final RestResponse<?> res) {
    if (res.isErroneous()) {
      throw new AssertionError("Failed to await result."
          + "msg: " + res.getErrorMessage());
    }
  }

  private static void throwOnNoError(final RestResponse<?> res) {
    if (!res.isErroneous()) {
      throw new AssertionError("Failed to get erroneous result.");
    }
  }

  static WebSocketClient makeWsRequest(
      final URI baseUri,
      final String sql,
      final Object listener,
      final Optional<MediaType> mediaType,
      final Optional<MediaType> contentType,
      final Optional<Credentials> credentials
  ) {
    try {
      final WebSocketClient wsClient = new WebSocketClient();
      wsClient.start();

      final ClientUpgradeRequest request = new ClientUpgradeRequest();

      credentials.ifPresent(creds -> request
          .setHeader(HttpHeaders.AUTHORIZATION, "Basic " + buildBasicAuthHeader(creds)));

      mediaType.ifPresent(mt -> request.setHeader(HttpHeaders.ACCEPT, mt.toString()));
      contentType.ifPresent(ct -> request.setHeader(HttpHeaders.CONTENT_TYPE, ct.toString()));

      final URI wsUri = baseUri.resolve("/ws/query?request=" + buildStreamingRequest(sql));

      wsClient.connect(listener, wsUri, request);

      return wsClient;
    } catch (final Exception e) {
      throw new RuntimeException("failed to create ws client", e);
    }
  }

  private static String buildBasicAuthHeader(final Credentials credentials) {
    return Base64.getEncoder().encodeToString(
        (credentials.username + ":" + credentials.password).getBytes(Charset.defaultCharset()));
  }

  private static String buildStreamingRequest(final String sql) {
    return UrlEscapers.urlFormParameterEscaper()
        .escape("{"
            + " \"ksql\": \"" + sql + "\""
            + "}");
  }
}
