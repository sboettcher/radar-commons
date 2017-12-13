/*
 * Copyright 2017 The Hyve and King's College London
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.producer.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.avro.Schema;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.radarcns.config.ServerConfig;
import org.radarcns.data.AvroRecordData;
import org.radarcns.data.Record;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.passive.phone.PhoneLight;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.producer.KafkaTopicSender;
import org.radarcns.topic.AvroTopic;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RestSenderTest {
    private SchemaRetriever retriever;
    private RestSender sender;

    @Rule
    public MockWebServer webServer = new MockWebServer();

    @Before
    public void setUp() {
        this.retriever = mock(SchemaRetriever.class);

        ServerConfig config = new ServerConfig(webServer.url("/").url());
        this.sender = new RestSender.Builder()
                .server(config)
                .schemaRetriever(retriever)
                .connectionPool(new ManagedConnectionPool())
                .build();
    }

    @Test
    public void sender() throws Exception {
        Schema keySchema = ObservationKey.getClassSchema();
        Schema valueSchema = PhoneLight.getClassSchema();
        AvroTopic<ObservationKey, PhoneLight> topic = new AvroTopic<>("test",
                keySchema, valueSchema, ObservationKey.class, PhoneLight.class);
        Headers headers = new Headers.Builder()
                .add("Cookie", "ab")
                .add("Cookie", "bc")
                .build();
        sender.setHeaders(headers);
        KafkaTopicSender<ObservationKey, PhoneLight> topicSender = sender.sender(topic);

        ObservationKey key = new ObservationKey("test","a", "b");
        PhoneLight value = new PhoneLight(0.1, 0.2, 0.3f);
        ParsedSchemaMetadata keySchemaMetadata = new ParsedSchemaMetadata(10, 2, keySchema);
        ParsedSchemaMetadata valueSchemaMetadata = new ParsedSchemaMetadata(10, 2, valueSchema);

        when(retriever
                .getOrSetSchemaMetadata("test", false, keySchema, -1))
                .thenReturn(keySchemaMetadata);
        when(retriever
                .getOrSetSchemaMetadata("test", true, valueSchema, -1))
                .thenReturn(valueSchemaMetadata);

        webServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"offset\": 100}"));

        topicSender.send(new AvroRecordData<>(topic,
                Collections.singleton(new Record<>(key, value))));

        verify(retriever, times(1))
                .getOrSetSchemaMetadata("test", false, keySchema, -1);
        verify(retriever, times(1))
                .getOrSetSchemaMetadata("test", true, valueSchema, -1);

        RecordedRequest request = webServer.takeRequest();
        assertEquals("/topics/test", request.getPath());
        ObjectReader reader = new ObjectMapper().readerFor(JsonNode.class);
        JsonNode body = reader.readValue(request.getBody().inputStream());
        assertEquals(10, body.get("key_schema_id").asInt());
        assertEquals(10, body.get("value_schema_id").asInt());
        JsonNode records = body.get("records");
        assertEquals(JsonNodeType.ARRAY, records.getNodeType());
        assertEquals(1, records.size());
        checkChildren(records);
        Headers receivedHeaders = request.getHeaders();
        assertEquals(Arrays.asList("ab", "bc"), receivedHeaders.values("Cookie"));
    }

    @Test
    public void sendTwo() throws Exception {
        Schema keySchema = ObservationKey.getClassSchema();
        Schema valueSchema = PhoneLight.getClassSchema();
        AvroTopic<ObservationKey, PhoneLight> topic = new AvroTopic<>("test",
                keySchema, valueSchema, ObservationKey.class, PhoneLight.class);
        KafkaTopicSender<ObservationKey, PhoneLight> topicSender = sender.sender(topic);

        ObservationKey key = new ObservationKey("test", "a", "b");
        PhoneLight value = new PhoneLight(0.1, 0.2, 0.3f);
        ParsedSchemaMetadata keySchemaMetadata = new ParsedSchemaMetadata(10, 2, keySchema);
        ParsedSchemaMetadata valueSchemaMetadata = new ParsedSchemaMetadata(10, 2, valueSchema);

        when(retriever
                .getOrSetSchemaMetadata("test", false, keySchema, -1))
                .thenReturn(keySchemaMetadata);
        when(retriever
                .getOrSetSchemaMetadata("test", true, valueSchema, -1))
                .thenReturn(valueSchemaMetadata);

        webServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"offset\": 100}"));

        topicSender.send(new AvroRecordData<>(topic, Arrays.asList(
                new Record<>(key, value),
                new Record<>(key, value))));

        verify(retriever, times(1))
                .getOrSetSchemaMetadata("test", false, keySchema, -1);
        verify(retriever, times(1))
                .getOrSetSchemaMetadata("test", true, valueSchema, -1);

        RecordedRequest request = webServer.takeRequest();
        assertEquals("/topics/test", request.getPath());
        ObjectReader reader = new ObjectMapper().readerFor(JsonNode.class);
        JsonNode body = reader.readValue(request.getBody().inputStream());
        assertEquals(10, body.get("key_schema_id").asInt());
        assertEquals(10, body.get("value_schema_id").asInt());
        JsonNode records = body.get("records");
        assertEquals(JsonNodeType.ARRAY, records.getNodeType());
        assertEquals(2, records.size());
        checkChildren(records);
    }

    @Test
    public void resetConnection() throws Exception {
        int n_requests = 0;

        webServer.enqueue(new MockResponse().setResponseCode(500));
        assertFalse(sender.isConnected());
        assertEquals(++n_requests, webServer.getRequestCount());
        RecordedRequest request = webServer.takeRequest();
        assertEquals("/", request.getPath());
        assertEquals("HEAD", request.getMethod());
        webServer.enqueue(new MockResponse().setResponseCode(500));
        assertFalse(sender.resetConnection());
        assertEquals(++n_requests, webServer.getRequestCount());
        request = webServer.takeRequest();
        assertEquals("/", request.getPath());
        assertEquals("HEAD", request.getMethod());
        webServer.enqueue(new MockResponse());
        assertFalse(sender.isConnected());
        assertEquals(n_requests, webServer.getRequestCount());
        assertTrue(sender.resetConnection());
        assertEquals(++n_requests, webServer.getRequestCount());
        request = webServer.takeRequest();
        assertEquals("/", request.getPath());
        assertEquals("HEAD", request.getMethod());
    }

    @Test
    public void resetConnectionUnauthorized() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(401));
        try {
            sender.isConnected();
            fail("Authentication exception expected");
        } catch (AuthenticationException ex) {
            // success
        }
        try {
            sender.isConnected();
            fail("Authentication exception expected");
        } catch (AuthenticationException ex) {
            // success
        }
        webServer.enqueue(new MockResponse().setResponseCode(401));
        try {
            sender.resetConnection();
            fail("Authentication exception expected");
        } catch (AuthenticationException ex) {
            assertEquals(2, webServer.getRequestCount());
            // success
        }
        webServer.enqueue(new MockResponse().setResponseCode(200));
        try {
            assertTrue(sender.resetConnection());
        } catch (AuthenticationException ex) {
            assertEquals(3, webServer.getRequestCount());
            fail("Unexpected authentication failure");
        }
    }

    @Test
    public void withCompression() throws IOException, InterruptedException {
        sender.setCompression(true);
        webServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"offset\": 100}"));
        Schema keySchema = ObservationKey.getClassSchema();
        Schema valueSchema = PhoneLight.getClassSchema();
        AvroTopic<ObservationKey, PhoneLight> topic = new AvroTopic<>("test",
                keySchema, valueSchema, ObservationKey.class, PhoneLight.class);
        KafkaTopicSender<ObservationKey, PhoneLight> topicSender = sender.sender(topic);

        ObservationKey key = new ObservationKey("test", "a", "b");
        PhoneLight value = new PhoneLight(0.1, 0.2, 0.3f);
        ParsedSchemaMetadata keySchemaMetadata = new ParsedSchemaMetadata(10, 2, keySchema);
        ParsedSchemaMetadata valueSchemaMetadata = new ParsedSchemaMetadata(10, 2, valueSchema);

        when(retriever
                .getOrSetSchemaMetadata("test", false, keySchema, -1))
                .thenReturn(keySchemaMetadata);
        when(retriever
                .getOrSetSchemaMetadata("test", true, valueSchema, -1))
                .thenReturn(valueSchemaMetadata);

        topicSender.send(new AvroRecordData<>(topic,
                Collections.singleton(new Record<>(key, value))));

        RecordedRequest request = webServer.takeRequest();
        assertEquals("gzip", request.getHeader("Content-Encoding"));

        ObjectReader reader = new ObjectMapper().readerFor(JsonNode.class);

        try (InputStream in = request.getBody().inputStream();
                GZIPInputStream gzipIn = new GZIPInputStream(in)) {
            JsonNode body = reader.readValue(gzipIn);
            assertEquals(10, body.get("key_schema_id").asInt());
            assertEquals(10, body.get("value_schema_id").asInt());
            JsonNode records = body.get("records");
            assertEquals(JsonNodeType.ARRAY, records.getNodeType());
            assertEquals(1, records.size());
            checkChildren(records);
        }
    }

    private static void checkChildren(JsonNode records) {
        for (JsonNode child : records) {
            JsonNode jsonKey = child.get("key");
            assertEquals(JsonNodeType.OBJECT, jsonKey.getNodeType());
            assertEquals("a", jsonKey.get("userId").asText());
            assertEquals("b", jsonKey.get("sourceId").asText());
            JsonNode jsonValue = child.get("value");
            assertEquals(JsonNodeType.OBJECT, jsonValue.getNodeType());
            assertEquals(0.1, jsonValue.get("time").asDouble(), 0);
            assertEquals(0.2, jsonValue.get("timeReceived").asDouble(), 0);
            assertEquals(0.3f, (float)jsonValue.get("light").asDouble(), 0);
        }
    }
}
