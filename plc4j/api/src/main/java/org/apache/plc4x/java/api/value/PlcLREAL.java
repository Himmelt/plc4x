/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.plc4x.java.api.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.plc4x.java.api.exceptions.PlcInvalidFieldException;

import java.math.BigDecimal;
import java.math.BigInteger;

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "className")
public class PlcLREAL extends PlcIECValue<Double> {

    static Double minValue = -Double.MAX_VALUE;
    static Double maxValue = Double.MAX_VALUE;

    public PlcLREAL(Boolean value) {
        super();
        this.value = value ? (Double) 1.0 : (Double) 0.0;
        this.isNullable = false;
    }

    public PlcLREAL(Byte value) {
        super();
        this.value = value.doubleValue();
        this.isNullable = false;
    }

    public PlcLREAL(Short value) {
        super();
        this.value = value.doubleValue();
        this.isNullable = false;
    }

    public PlcLREAL(Integer value) {
        super();
        this.value = value.doubleValue();
        this.isNullable = false;
    }

    public PlcLREAL(Float value) {
        super();
        this.value = value.doubleValue();
        this.isNullable = false;
    }

    public PlcLREAL(Double value) {
        super();
        this.value = value;
        this.isNullable = false;
    }

    public PlcLREAL(BigInteger value) {
        super();
        BigDecimal val = new BigDecimal(value);
        if ((val.compareTo(BigDecimal.valueOf(minValue)) >= 0) && (val.compareTo(BigDecimal.valueOf(maxValue)) <= 0)) {
            this.value = val.doubleValue();
            this.isNullable = true;
        } else {
          throw new PlcInvalidFieldException("Value of type " + value +
            " is out of range " + minValue + " - " + maxValue + " for a " +
            this.getClass().getSimpleName() + " Value");
        }
    }

    public PlcLREAL(BigDecimal value) {
        super();
        if ((value.compareTo(BigDecimal.valueOf(minValue)) >= 0) && (value.compareTo(BigDecimal.valueOf(maxValue)) <= 0) && (value.scale() <= 0)) {
            this.value = value.doubleValue();
            this.isNullable = true;
        } else {
          throw new PlcInvalidFieldException("Value of type " + value +
            " is out of range " + minValue + " - " + maxValue + " for a " +
            this.getClass().getSimpleName() + " Value");
        }
    }

    public PlcLREAL(String value) {
        super();
        try {
            this.value = Double.parseDouble(value);
            this.isNullable = false;
        }
        catch(Exception e) {
          throw new PlcInvalidFieldException("Value of type " + value +
            " is out of range " + minValue + " - " + maxValue + " for a " +
            this.getClass().getSimpleName() + " Value");
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public PlcLREAL(@JsonProperty("value") double value) {
        super();
        this.value = value;
        this.isNullable = false;
    }

    @Override
    @JsonIgnore
    public boolean isBoolean() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean getBoolean() {
        return (value != null) && !value.equals(0);
    }

    @Override
    @JsonIgnore
    public boolean isByte() {
        return (value != null) && (value <= Byte.MAX_VALUE) && (value >= Byte.MIN_VALUE);
    }

    @Override
    @JsonIgnore
    public byte getByte() {
        return value.byteValue();
    }

    @Override
    @JsonIgnore
    public boolean isShort() {
        return (value != null) && (value <= Short.MAX_VALUE) && (value >= Short.MIN_VALUE);
    }

    @Override
    @JsonIgnore
    public short getShort() {
        return value.shortValue();
    }

    @Override
    @JsonIgnore
    public boolean isInteger() {
        return (value != null) && (value <= Integer.MAX_VALUE) && (value >= Integer.MIN_VALUE);
    }

    @Override
    @JsonIgnore
    public int getInteger() {
        return value.intValue();
    }

    @Override
    @JsonIgnore
    public boolean isLong() {
        return (value != null) && (value <= Long.MAX_VALUE) && (value >= Long.MIN_VALUE);
    }

    @Override
    @JsonIgnore
    public long getLong() {
        return value.longValue();
    }

    @Override
    @JsonIgnore
    public boolean isBigInteger() {
        return true;
    }

    @Override
    @JsonIgnore
    public BigInteger getBigInteger() {
        return BigInteger.valueOf(getLong());
    }

    @Override
    @JsonIgnore
    public boolean isFloat() {
        return (value != null) && (value <= Float.MAX_VALUE) && (value >= -Float.MAX_VALUE);
    }

    @Override
    @JsonIgnore
    public float getFloat() {
        return value.floatValue();
    }

    @Override
    @JsonIgnore
    public boolean isDouble() {
        return true;
    }

    @Override
    @JsonIgnore
    public double getDouble() {
        return value;
    }

    @Override
    @JsonIgnore
    public boolean isBigDecimal() {
        return true;
    }

    @Override
    @JsonIgnore
    public BigDecimal getBigDecimal() {
        return new BigDecimal(value);
    }

    @Override
    @JsonIgnore
    public boolean isString() {
        return true;
    }

    @Override
    @JsonIgnore
    public String getString() {
        return toString();
    }

    @Override
    @JsonIgnore
    public String toString() {
        return Double.toString(value);
    }

    @JsonIgnore
    public double getLREAL() {
        return value;
    }

    @JsonIgnore
    public byte[] getBytes() {
        long longBits =  Double.doubleToRawLongBits(value);
        byte[] bytes = new byte[8];
        bytes[0] = (byte) ((longBits >> 56) & 0xff);
        bytes[1] = (byte) ((longBits >> 48) & 0xff);
        bytes[2] = (byte) ((longBits >> 40) & 0xff);
        bytes[3] = (byte) ((longBits >> 32) & 0xff);
        bytes[4] = (byte) ((longBits >> 24) & 0xff);
        bytes[5] = (byte) ((longBits >> 16) & 0xff);
        bytes[6] = (byte) ((longBits >> 8) & 0xff);
        bytes[7] = (byte) (longBits & 0xff);
        return bytes;
    }

}
