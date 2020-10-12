/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */
package org.apache.plc4x.java.ads;

import org.apache.plc4x.java.ads.configuration.AdsConfiguration;
import org.apache.plc4x.java.ads.field.AdsFieldHandler;
import org.apache.plc4x.java.ads.protocol.AdsProtocolLogic;
import org.apache.plc4x.java.ads.readwrite.AmsTCPPacket;
import org.apache.plc4x.java.ads.readwrite.io.AmsTCPPacketIO;
import org.apache.plc4x.java.spi.configuration.Configuration;
import org.apache.plc4x.java.spi.connection.GeneratedDriverBase;
import org.apache.plc4x.java.spi.connection.ProtocolStackConfigurer;
import org.apache.plc4x.java.spi.connection.SingleProtocolStackConfigurer;

/**
 * Implementation of the ADS discovery protocol.
 */
public class ADSDiscoveryPlcDriver extends GeneratedDriverBase<AmsTCPPacket> {

    public static final int UDP_PORT = 48899;

    @Override
    public String getProtocolCode() {
        return "ads.discovery";
    }

    @Override
    public String getProtocolName() {
        return "Beckhoff TwinCat ADS-Discovery";
    }

    @Override
    protected boolean canRead() {
        return true;
    }

    @Override
    protected boolean canWrite() {
        return true;
    }

    @Override
    protected Class<? extends Configuration> getConfigurationType() {
        return AdsConfiguration.class;
    }

    @Override
    protected String getDefaultTransport() {
        return "udp";
    }

    @Override
    protected AdsFieldHandler getFieldHandler() {
        return new AdsFieldHandler();
    }

    @Override
    protected ProtocolStackConfigurer<AmsTCPPacket> getStackConfigurer() {
        return SingleProtocolStackConfigurer.builder(AmsTCPPacket.class, AmsTCPPacketIO.class)
            .withProtocol(AdsProtocolLogic.class)
            .littleEndian()
            .build();
    }

}
