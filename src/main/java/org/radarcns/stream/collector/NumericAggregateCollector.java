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

package org.radarcns.stream.collector;

import static org.radarcns.util.Serialization.floatToDouble;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.specific.SpecificRecord;

/**
 * Java class to aggregate data using Kafka Streams. Double is the base type.
 * Only the sum and sorted history are collected, other values are calculated on request.
 */
public class NumericAggregateCollector implements RecordCollector {
    private final String name;
    private final int pos;
    private final Type fieldType;
    private BigDecimal sum;
    private final List<Double> history;

    @JsonCreator
    public NumericAggregateCollector(
            @JsonProperty("name") String name, @JsonProperty("pos") int pos,
            @JsonProperty("fieldType") Type fieldType, @JsonProperty("sum") BigDecimal sum,
            @JsonProperty("history") List<Double> history) {
        this.name = name;
        this.pos = pos;
        this.fieldType = fieldType;
        this.sum = sum;
        this.history = new ArrayList<>(history);
    }

    public NumericAggregateCollector(String fieldName) {
        this(fieldName, null);
    }

    public NumericAggregateCollector(String fieldName, Schema schema) {
        sum = BigDecimal.ZERO;
        this.history = new ArrayList<>();

        this.name = fieldName;
        if (schema == null) {
            this.pos = -1;
            this.fieldType = null;
        } else {
            Field field = schema.getField(fieldName);
            if (field == null) {
                throw new IllegalArgumentException(
                        "Field " + fieldName + " does not exist in schema " + schema.getFullName());
            }
            this.pos = field.pos();

            Type apparentType = field.schema().getType();
            if (apparentType == Type.UNION) {
                for (Schema subSchema : field.schema().getTypes()) {
                    if (subSchema.getType() != Type.NULL) {
                        apparentType = subSchema.getType();
                        break;
                    }
                }
            }
            fieldType = apparentType;

            if (fieldType != Type.DOUBLE
                    && fieldType != Type.FLOAT
                    && fieldType != Type.INT
                    && fieldType != Type.LONG) {
                throw new IllegalArgumentException("Field " + fieldName + " is not a number type.");
            }
        }
    }

    @Override
    public NumericAggregateCollector add(SpecificRecord record) {
        if (pos == -1) {
            throw new IllegalStateException(
                    "Cannot add record without specifying a schema in the constructor.");
        }
        Number value = (Number)record.get(pos);
        if (value == null) {
            return this;
        }
        if (fieldType == Type.FLOAT) {
            return add(Double.parseDouble(value.toString()));
        } else {
            return add(value.doubleValue());
        }
    }

    public NumericAggregateCollector add(float value) {
        return this.add(floatToDouble(value));
    }

    /**
     * Add a sample to the collection.
     * @param value new sample that has to be analysed
     */
    public NumericAggregateCollector add(double value) {
        sum = sum.add(BigDecimal.valueOf(value));

        int index = Collections.binarySearch(history, value);
        if (index >= 0) {
            history.add(index, value);
        } else {
            history.add(-index - 1, value);
        }

        return this;
    }

    @Override
    public String toString() {
        return "DoubleValueCollector{"
                + "name=" + getName()
                + ", min=" + getMin()
                + ", max=" + getMax()
                + ", sum=" + getSum()
                + ", count=" + getCount()
                + ", mean=" + getMean()
                + ", quartile=" + getQuartile()
                + ", history=" + history + '}';
    }

    public double getMin() {
        return history.get(0);
    }

    public double getMax() {
        return history.get(history.size() - 1);
    }

    public double getSum() {
        return sum.doubleValue();
    }

    public int getCount() {
        return history.size();
    }

    public double getMean() {
        return sum.doubleValue() / history.size();
    }

    public List<Double> getQuartile() {
        int length = history.size();

        List<Double> quartiles;
        if (length == 1) {
            Double elem = history.get(0);
            quartiles = Arrays.asList(elem, elem, elem);
        } else {
            quartiles = new ArrayList<>(3);
            for (int i = 1; i <= 3; i++) {
                double pos = i * (length + 1) / 4.0d;  // == i * 25 * (length + 1) / 100
                int intPos = (int) pos;
                if (intPos == 0) {
                    quartiles.add(history.get(0));
                } else if (intPos == length) {
                    quartiles.add(history.get(length - 1));
                } else {
                    double diff = pos - intPos;
                    double base = history.get(intPos - 1);
                    quartiles.add(base + diff * (history.get(intPos) - base));
                }
            }
        }

        return quartiles;
    }

    public double getInterQuartileRange() {
        List<Double> quartiles = getQuartile();
        return BigDecimal.valueOf(quartiles.get(2))
                .subtract(BigDecimal.valueOf(quartiles.get(0))).doubleValue();
    }

    public String getName() {
        return name;
    }
}
