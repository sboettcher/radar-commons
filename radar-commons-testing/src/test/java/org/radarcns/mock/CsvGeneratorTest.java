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

package org.radarcns.mock;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.mock.config.MockDataConfig;
import org.radarcns.mock.data.CsvGenerator;
import org.radarcns.mock.data.MockRecordValidatorTest;
import org.radarcns.mock.data.RecordGenerator;
import org.radarcns.util.CsvParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class CsvGeneratorTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MockDataConfig makeConfig() throws IOException {
        return MockRecordValidatorTest.makeConfig(folder);
    }

    @Test
    public void generateMockConfig() throws IOException {
        CsvGenerator generator = new CsvGenerator();

        MockDataConfig config = makeConfig();
        generator.generate(config, 100_000L, folder.getRoot());

        CsvParser parser = new CsvParser(new BufferedReader(new FileReader(config.getDataFile())));
        List<String> headers = Arrays.asList(
                "projectId", "userId", "sourceId", "time", "timeReceived", "light");
        assertEquals(headers, parser.parseLine());

        int n = 0;
        List<String> line;
        while ((line = parser.parseLine()) != null) {
            String value = line.get(5);
            assertNotEquals("NaN", value);
            assertNotEquals("Infinity", value);
            assertNotEquals("-Infinity", value);
            // no decimals lost or appended
            assertEquals(value, Float.valueOf(value).toString());
            n++;
        }
        assertEquals(100, n);
    }

    @Test
    public void generateGenerator()
            throws IOException, ClassNotFoundException, NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        CsvGenerator generator = new CsvGenerator();

        MockDataConfig config = makeConfig();

        final String time = Double.toString(System.currentTimeMillis() / 1000d);

        RecordGenerator<ObservationKey> recordGenerator = new RecordGenerator<ObservationKey>(
                config, ObservationKey.class) {
            @Override
            public Iterator<List<String>> iterateRawValues(ObservationKey key, long duration) {
                return Collections.singletonList(
                        Arrays.asList("test", "UserID_0", "SourceID_0", time, time,
                                Float.valueOf((float)0.123112412410423518).toString()))
                        .iterator();
            }
        };

        generator.generate(recordGenerator, 1000L, new File(config.getDataFile()));

        CsvParser parser = new CsvParser(new BufferedReader(new FileReader(config.getDataFile())));
        assertEquals(recordGenerator.getHeader(), parser.parseLine());
        // float will cut off a lot of decimals
        assertEquals(Arrays.asList("test", "UserID_0", "SourceID_0", time, time, "0.12311241"),
                parser.parseLine());
    }
}