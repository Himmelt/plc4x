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
package org.apache.plc4x.java.modbus.protocol;

import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.messages.*;
import org.apache.plc4x.java.api.value.*;
import org.apache.plc4x.java.api.model.PlcField;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.modbus.config.ModbusConfiguration;
import org.apache.plc4x.java.modbus.field.ModbusField;
import org.apache.plc4x.java.modbus.field.ModbusFieldCoil;
import org.apache.plc4x.java.modbus.field.ModbusFieldDiscreteInput;
import org.apache.plc4x.java.modbus.field.ModbusFieldHoldingRegister;
import org.apache.plc4x.java.modbus.field.ModbusFieldInputRegister;
import org.apache.plc4x.java.modbus.field.ModbusExtendedRegister;
import org.apache.plc4x.java.modbus.readwrite.*;
import org.apache.plc4x.java.modbus.readwrite.types.*;
import org.apache.plc4x.java.modbus.readwrite.io.DataItemIO;
import org.apache.plc4x.java.spi.ConversationContext;
import org.apache.plc4x.java.spi.Plc4xProtocolBase;
import org.apache.plc4x.java.spi.configuration.HasConfiguration;
import org.apache.plc4x.java.spi.generation.ParseException;
import org.apache.plc4x.java.spi.generation.ReadBuffer;
import org.apache.plc4x.java.spi.messages.DefaultPlcReadRequest;
import org.apache.plc4x.java.spi.messages.DefaultPlcReadResponse;
import org.apache.plc4x.java.spi.messages.DefaultPlcWriteRequest;
import org.apache.plc4x.java.spi.messages.DefaultPlcWriteResponse;
import org.apache.plc4x.java.spi.messages.utils.ResponseItem;
import org.apache.plc4x.java.spi.transaction.RequestTransactionManager;
import org.apache.commons.lang3.ArrayUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class ModbusProtocolLogic extends Plc4xProtocolBase<ModbusTcpADU> implements HasConfiguration<ModbusConfiguration> {

    private Duration requestTimeout;
    private short unitIdentifier;
    private RequestTransactionManager tm;
    private AtomicInteger transactionIdentifierGenerator = new AtomicInteger(10);
    private final static int FC_EXTENDED_REGISTERS_GROUP_HEADER_LENGTH = 2;
    private final static int FC_EXTENDED_REGISTERS_FILE_RECORD_LENGTH = 10000;

    @Override
    public void setConfiguration(ModbusConfiguration configuration) {
        this.requestTimeout = Duration.ofMillis(configuration.getRequestTimeout());
        this.unitIdentifier = (short) configuration.getUnitIdentifier();
        this.tm = new RequestTransactionManager(1);
        this.transactionIdentifierGenerator = new AtomicInteger(10);
    }

    @Override
    public void close(ConversationContext<ModbusTcpADU> context) {
        // Nothing to do here ...
    }

    @Override
    public CompletableFuture<PlcReadResponse> read(PlcReadRequest readRequest) {
        CompletableFuture<PlcReadResponse> future = new CompletableFuture<>();
        DefaultPlcReadRequest request = (DefaultPlcReadRequest) readRequest;

        // 1. Sort all items by type:
        //      - DiscreteInput     (read-only)     --> ModbusPduReadDiscreteInputsRequest
        //      - Coil              (read-write)    --> ModbusPduReadCoilsRequest
        //      - InputRegister     (read-only)     --> ModbusPduReadInputRegistersRequest
        //      - HoldingRegister   (read-write)    --> ModbusPduReadHoldingRegistersRequest
        //      - FifoQueue         (read-only)     --> ModbusPduReadFifoQueueRequest
        //      - FileRecord        (read-write)    --> ModbusPduReadFileRecordRequest
        // 2. Split up into multiple sub-requests

        // Example for sending a request ...
        if(request.getFieldNames().size() == 1) {
            String fieldName = request.getFieldNames().iterator().next();
            ModbusField field = (ModbusField) request.getField(fieldName);
            final ModbusPDU requestPdu = getReadRequestPdu(field);
            int transactionIdentifier = transactionIdentifierGenerator.getAndIncrement();
            // If we've reached the max value for a 16 bit transaction identifier, reset back to 1
            if(transactionIdentifierGenerator.get() == 0xFFFF) {
                transactionIdentifierGenerator.set(1);
            }
            ModbusTcpADU modbusTcpADU = new ModbusTcpADU(transactionIdentifier, unitIdentifier, requestPdu);
            RequestTransactionManager.RequestTransaction transaction = tm.startRequest();
            transaction.submit(() -> context.sendRequest(modbusTcpADU)
                .expectResponse(ModbusTcpADU.class, requestTimeout)
                .onTimeout(future::completeExceptionally)
                .onError((p, e) -> future.completeExceptionally(e))
                .check(p -> p.getTransactionIdentifier() == transactionIdentifier)
                .unwrap(ModbusTcpADU::getPdu)
                .handle(responsePdu -> {
                    // Try to decode the response data based on the corresponding request.
                    PlcValue plcValue = null;
                    PlcResponseCode responseCode;
                    // Check if the response was an error response.
                    if (responsePdu instanceof ModbusPDUError) {
                        ModbusPDUError errorResponse = (ModbusPDUError) responsePdu;
                        switch (errorResponse.getExceptionCode()) {
                            case 1:
                                // This implies the received function code is not supported.
                                responseCode = PlcResponseCode.UNSUPPORTED;
                                break;
                            case 2:
                                responseCode = PlcResponseCode.INVALID_ADDRESS;
                                break;
                            case 3:
                                responseCode = PlcResponseCode.INVALID_DATA;
                                break;
                            case 4:
                                responseCode = PlcResponseCode.REMOTE_ERROR;
                                break;
                            case 6:
                                responseCode = PlcResponseCode.REMOTE_BUSY;
                                break;
                            default:
                                // This generally implies that something wen't wrong which we didn't anticipate.
                                responseCode = PlcResponseCode.INTERNAL_ERROR;
                                break;
                        }
                    } else {
                        try {
                            plcValue = toPlcValue(requestPdu, responsePdu, field.getDataType());
                            responseCode = PlcResponseCode.OK;
                        } catch (ParseException e) {
                            // Add an error response code ...
                            responseCode = PlcResponseCode.INTERNAL_ERROR;
                        }
                    }

                    // Prepare the response.
                    PlcReadResponse response = new DefaultPlcReadResponse(request,
                        Collections.singletonMap(fieldName, new ResponseItem<>(responseCode, plcValue)));

                    // Pass the response back to the application.
                    future.complete(response);

                    // Finish the request-transaction.
                    transaction.endRequest();
            }));
        } else {
            future.completeExceptionally(new PlcRuntimeException("Modbus only supports single filed requests"));
        }
        return future;
    }

    @Override
    public CompletableFuture<PlcWriteResponse> write(PlcWriteRequest writeRequest) {
        CompletableFuture<PlcWriteResponse> future = new CompletableFuture<>();
        DefaultPlcWriteRequest request = (DefaultPlcWriteRequest) writeRequest;

        // 1. Sort all items by type:
        //      - DiscreteInput     (read-only)     --> Error
        //      - Coil              (read-write)    --> ModbusPduWriteSingleCoilRequest / ModbusPduWriteMultipleCoilsRequest
        //      - InputRegister     (read-only)     --> Error
        //      - HoldingRegister   (read-write)    --> ModbusPduWriteSingleRegisterRequest / ModbusPduWriteMultipleRegistersRequest
        //      - FifoQueue         (read-only)     --> Error
        //      - FileRecord        (read-write)    --> ModbusPduWriteFileRecordRequest
        // 2. Split up into multiple sub-requests
        if(request.getFieldNames().size() == 1) {
            String fieldName = request.getFieldNames().iterator().next();
            PlcField field = request.getField(fieldName);
            final ModbusPDU requestPdu = getWriteRequestPdu(field, ((DefaultPlcWriteRequest) writeRequest).getPlcValue(fieldName));
            int transactionIdentifier = transactionIdentifierGenerator.getAndIncrement();
            // If we've reached the max value for a 16 bit transaction identifier, reset back to 1
            if(transactionIdentifierGenerator.get() == 0xFFFF) {
                transactionIdentifierGenerator.set(1);
            }
            ModbusTcpADU modbusTcpADU = new ModbusTcpADU(transactionIdentifier, unitIdentifier, requestPdu);
            RequestTransactionManager.RequestTransaction transaction = tm.startRequest();
            transaction.submit(() -> context.sendRequest(modbusTcpADU)
                .expectResponse(ModbusTcpADU.class, requestTimeout)
                .onTimeout(future::completeExceptionally)
                .onError((p, e) -> future.completeExceptionally(e))
                .check(p -> p.getTransactionIdentifier() == transactionIdentifier)
                .unwrap(ModbusTcpADU::getPdu)
                .handle(responsePdu -> {
                    // TODO: Check the correct number of elements were written.

                    // Try to decode the response data based on the corresponding request.
                    PlcValue plcValue = null;
                    PlcResponseCode responseCode = PlcResponseCode.OK;

                    // Prepare the response.
                    PlcWriteResponse response = new DefaultPlcWriteResponse(request,
                        Collections.singletonMap(fieldName, responseCode));

                    // Pass the response back to the application.
                    future.complete(response);

                    // Finish the request-transaction.
                    transaction.endRequest();
                }));

        } else {
            future.completeExceptionally(new PlcRuntimeException("Modbus only supports single filed requests"));
        }
        return future;
    }

    private ModbusPDU getReadRequestPdu(PlcField field) {
        if(field instanceof ModbusFieldDiscreteInput) {
            ModbusFieldDiscreteInput discreteInput = (ModbusFieldDiscreteInput) field;
            return new ModbusPDUReadDiscreteInputsRequest(discreteInput.getAddress(), discreteInput.getQuantity());
        } else if(field instanceof ModbusFieldCoil) {
            ModbusFieldCoil coil = (ModbusFieldCoil) field;
            return new ModbusPDUReadCoilsRequest(coil.getAddress(), coil.getQuantity());
        } else if(field instanceof ModbusFieldInputRegister) {
            ModbusFieldInputRegister inputRegister = (ModbusFieldInputRegister) field;
            return new ModbusPDUReadInputRegistersRequest(inputRegister.getAddress(), inputRegister.getLengthWords());
        } else if(field instanceof ModbusFieldHoldingRegister) {
            ModbusFieldHoldingRegister holdingRegister = (ModbusFieldHoldingRegister) field;
            return new ModbusPDUReadHoldingRegistersRequest(holdingRegister.getAddress(), holdingRegister.getLengthWords());
        } else if(field instanceof ModbusExtendedRegister) {
            ModbusExtendedRegister extendedRegister = (ModbusExtendedRegister) field;
            int group1_address = extendedRegister.getAddress() % 10000;
            int group2_address = 0;
            int group1_quantity, group2_quantity;
            short group1_file_number = (short) (Math.floor(extendedRegister.getAddress() / 10000) + 1);
            short group2_file_number;
            ModbusPDUReadFileRecordRequestItem[] itemArray;

            if ((group1_address + extendedRegister.getLengthWords()) <= FC_EXTENDED_REGISTERS_FILE_RECORD_LENGTH) {
              //If request doesn't span file records, use a single group
              group1_quantity = extendedRegister.getLengthWords();
              ModbusPDUReadFileRecordRequestItem group1 = new ModbusPDUReadFileRecordRequestItem((short) 6, group1_file_number, group1_address, group1_quantity);
              itemArray = new ModbusPDUReadFileRecordRequestItem[] {group1};
            } else {
              //If it doesn't span a file record. e.g. 609998[10] request 2 words in first group and 8 in second.
              group1_quantity = FC_EXTENDED_REGISTERS_FILE_RECORD_LENGTH - group1_address;
              group2_quantity = extendedRegister.getLengthWords() - group1_quantity;
              group2_file_number = (short) (group1_file_number + 1);
              ModbusPDUReadFileRecordRequestItem group1 = new ModbusPDUReadFileRecordRequestItem((short) 6, group1_file_number, group1_address, group1_quantity);
              ModbusPDUReadFileRecordRequestItem group2 = new ModbusPDUReadFileRecordRequestItem((short) 6, group2_file_number, group2_address, group2_quantity);
              itemArray = new ModbusPDUReadFileRecordRequestItem[] {group1, group2};
            }
            return new ModbusPDUReadFileRecordRequest(itemArray);
        }
        throw new PlcRuntimeException("Unsupported read field type " + field.getClass().getName());
    }

    private ModbusPDU getWriteRequestPdu(PlcField field, PlcValue plcValue) {
        if(field instanceof ModbusFieldCoil) {
            ModbusFieldCoil coil = (ModbusFieldCoil) field;
            return new ModbusPDUWriteMultipleCoilsRequest(coil.getAddress(), coil.getQuantity(),
                fromPlcValue(plcValue));
        } else if(field instanceof ModbusFieldHoldingRegister) {
            ModbusFieldHoldingRegister holdingRegister = (ModbusFieldHoldingRegister) field;
            return new ModbusPDUWriteMultipleHoldingRegistersRequest(holdingRegister.getAddress(),
                holdingRegister.getLengthWords(), fromPlcValue(plcValue));
        } else if(field instanceof ModbusExtendedRegister) {
            ModbusExtendedRegister extendedRegister = (ModbusExtendedRegister) field;
            int group1_address = extendedRegister.getAddress() % FC_EXTENDED_REGISTERS_FILE_RECORD_LENGTH;
            int group2_address = 0;
            int group1_quantity, group2_quantity;
            byte[] plcValue1, plcValue2;
            short group1_file_number = (short) (Math.floor(extendedRegister.getAddress() / FC_EXTENDED_REGISTERS_FILE_RECORD_LENGTH) + 1);
            short group2_file_number;
            ModbusPDUWriteFileRecordRequestItem[] itemArray;

            if ((group1_address + extendedRegister.getLengthWords()) <= FC_EXTENDED_REGISTERS_FILE_RECORD_LENGTH) {
              //If request doesn't span file records, use a single group
              group1_quantity = extendedRegister.getLengthWords();
              ModbusPDUWriteFileRecordRequestItem group1 = new ModbusPDUWriteFileRecordRequestItem((short) 6, group1_file_number, group1_address, fromPlcValue(plcValue));
              itemArray = new ModbusPDUWriteFileRecordRequestItem[] {group1};
            } else {
              //If it doesn span a file record. e.g. 609998[10] request 2 words in first group and 8 in second.
              group1_quantity = FC_EXTENDED_REGISTERS_FILE_RECORD_LENGTH - group1_address;
              group2_quantity = extendedRegister.getLengthWords() - group1_quantity;
              group2_file_number = (short) (group1_file_number + 1);

              plcValue1 = ArrayUtils.subarray(fromPlcValue(plcValue), 0, group1_quantity);
              plcValue2 = ArrayUtils.subarray(fromPlcValue(plcValue), group1_quantity, fromPlcValue(plcValue).length);
              ModbusPDUWriteFileRecordRequestItem group1 = new ModbusPDUWriteFileRecordRequestItem((short) 6, group1_file_number, group1_address, plcValue1);
              ModbusPDUWriteFileRecordRequestItem group2 = new ModbusPDUWriteFileRecordRequestItem((short) 6, group2_file_number, group2_address, plcValue2);
              itemArray = new ModbusPDUWriteFileRecordRequestItem[] {group1, group2};
            }
            return new ModbusPDUWriteFileRecordRequest(itemArray);
        }
        throw new PlcRuntimeException("Unsupported write field type " + field.getClass().getName());
    }

    private PlcValue toPlcValue(ModbusPDU request, ModbusPDU response, String dataType) throws ParseException {
        Short fieldDataType = ModbusDataType.valueOf(dataType).getValue();
        Short fieldDataTypeSize = ModbusDataType.valueOf(dataType).getDataTypeSize();

        if (request instanceof ModbusPDUReadDiscreteInputsRequest) {
            if (!(response instanceof ModbusPDUReadDiscreteInputsResponse)) {
                throw new PlcRuntimeException("Unexpected response type. " +
                    "Expected ModbusPDUReadDiscreteInputsResponse, but got " + response.getClass().getName());
            }
            ModbusPDUReadDiscreteInputsRequest req = (ModbusPDUReadDiscreteInputsRequest) request;
            ModbusPDUReadDiscreteInputsResponse resp = (ModbusPDUReadDiscreteInputsResponse) response;
            return readBooleanList(req.getQuantity(), resp.getValue());
        } else if (request instanceof ModbusPDUReadCoilsRequest) {
            if (!(response instanceof ModbusPDUReadCoilsResponse)) {
                throw new PlcRuntimeException("Unexpected response type. " +
                    "Expected ModbusPDUReadCoilsResponse, but got " + response.getClass().getName());
            }
            ModbusPDUReadCoilsRequest req = (ModbusPDUReadCoilsRequest) request;
            ModbusPDUReadCoilsResponse resp = (ModbusPDUReadCoilsResponse) response;
            return readBooleanList(req.getQuantity(), resp.getValue());
        } else if (request instanceof ModbusPDUReadInputRegistersRequest) {
            if (!(response instanceof ModbusPDUReadInputRegistersResponse)) {
                throw new PlcRuntimeException("Unexpected response type. " +
                    "Expected ModbusPDUReadInputRegistersResponse, but got " + response.getClass().getName());
            }
            ModbusPDUReadInputRegistersRequest req = (ModbusPDUReadInputRegistersRequest) request;
            ModbusPDUReadInputRegistersResponse resp = (ModbusPDUReadInputRegistersResponse) response;
            ReadBuffer io = new ReadBuffer(resp.getValue());
            return DataItemIO.staticParse(io, fieldDataType, (short) Math.round(req.getQuantity()/(fieldDataTypeSize/2.0f)));
        } else if (request instanceof ModbusPDUReadHoldingRegistersRequest) {
            if (!(response instanceof ModbusPDUReadHoldingRegistersResponse)) {
                throw new PlcRuntimeException("Unexpected response type. " +
                    "Expected ModbusPDUReadHoldingRegistersResponse, but got " + response.getClass().getName());
            }
            ModbusPDUReadHoldingRegistersRequest req = (ModbusPDUReadHoldingRegistersRequest) request;
            ModbusPDUReadHoldingRegistersResponse resp = (ModbusPDUReadHoldingRegistersResponse) response;
            ReadBuffer io = new ReadBuffer(resp.getValue());
            return DataItemIO.staticParse(io, fieldDataType, (short) Math.round(req.getQuantity()/(fieldDataTypeSize/2.0f)));
        } else if (request instanceof ModbusPDUReadFileRecordRequest) {
            if (!(response instanceof ModbusPDUReadFileRecordResponse)) {
                throw new PlcRuntimeException("Unexpected response type. " +
                    "Expected ModbusPDUReadFileRecordResponse, but got " + response.getClass().getName());
            }
            ModbusPDUReadFileRecordRequest req = (ModbusPDUReadFileRecordRequest) request;
            ModbusPDUReadFileRecordResponse resp = (ModbusPDUReadFileRecordResponse) response;
            ReadBuffer io;
            short dataLength;

            if (resp.getItems().length == 2 && resp.getItems().length == req.getItems().length) {
              //If request was split over file records, two groups in reponse should be received.
              io = new ReadBuffer(ArrayUtils.addAll(resp.getItems()[0].getData(), resp.getItems()[1].getData()));
              dataLength = (short) (resp.getItems()[0].getLengthInBytes() + resp.getItems()[1].getLengthInBytes() - (2 * FC_EXTENDED_REGISTERS_GROUP_HEADER_LENGTH));
            } else if (resp.getItems().length == 1 && resp.getItems().length == req.getItems().length) {
              //If request was within a single file record, one group should be received.
              io = new ReadBuffer(resp.getItems()[0].getData());
              dataLength = (short) (resp.getItems()[0].getLengthInBytes() - FC_EXTENDED_REGISTERS_GROUP_HEADER_LENGTH);
            } else {
              throw new PlcRuntimeException("Unexpected number of groups in response. " +
                  "Expected " + req.getItems().length + ", but got " + resp.getItems().length);
            }

            return DataItemIO.staticParse(io, fieldDataType, (short) Math.round((dataLength/2.0f)/(fieldDataTypeSize/2.0f)));
        }
        return null;
    }

    private byte[] fromPlcValue(PlcValue plcValue) {
        if(plcValue instanceof PlcList) {
            PlcList plcList = (PlcList) plcValue;
            BitSet booleans = null;
            List<Byte> bytes = null;
            Short fieldDataTypeSize;
            int b = 0;
            for (PlcValue value : plcList.getList()) {
                if(value instanceof PlcBOOL) {
                    if(booleans == null) {
                        booleans = new BitSet(plcList.getList().size());
                    }
                    PlcBOOL plcBool= (PlcBOOL) value;
                    booleans.set(b, plcBool.getBoolean());
                    b++;
                } else if((plcValue instanceof PlcSINT) && (plcList.getLength() % 2 == 0)) {
                    if(bytes == null) {
                        fieldDataTypeSize = ModbusDataType.valueOf("SINT").getDataTypeSize();
                        bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcSINT) value).getBytes();
                    bytes.add(tempBytes[0]);
                } else if((plcValue instanceof PlcUSINT) && (plcList.getLength() % 2 == 0)) {
                    if(bytes == null) {
                        fieldDataTypeSize = ModbusDataType.valueOf("USINT").getDataTypeSize();
                        bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcUSINT) value).getBytes();
                    bytes.add(tempBytes[0]);
                } else if((plcValue instanceof PlcBYTE) && (plcList.getLength() % 2 == 0)) {
                    if(bytes == null) {
                        fieldDataTypeSize = ModbusDataType.valueOf("BYTE").getDataTypeSize();
                        bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcBYTE) value).getBytes();
                    bytes.add(tempBytes[0]);
                } else if(plcValue instanceof PlcINT) {
                    if(bytes == null) {
                        fieldDataTypeSize = ModbusDataType.valueOf("INT").getDataTypeSize();
                        bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcINT) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                } else if(plcValue instanceof PlcUINT) {
                    if(bytes == null) {
                        fieldDataTypeSize = ModbusDataType.valueOf("UINT").getDataTypeSize();
                        bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcUINT) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                } else if(plcValue instanceof PlcWORD) {
                    if(bytes == null) {
                        fieldDataTypeSize = ModbusDataType.valueOf("WORD").getDataTypeSize();
                        bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcWORD) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                } else if(plcValue instanceof PlcDINT) {
                    if(bytes == null) {
                      fieldDataTypeSize = ModbusDataType.valueOf("DINT").getDataTypeSize();
                      bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcDINT) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                    bytes.add(tempBytes[2]);
                    bytes.add(tempBytes[3]);
                } else if(plcValue instanceof PlcUDINT) {
                    if(bytes == null) {
                      fieldDataTypeSize = ModbusDataType.valueOf("UDINT").getDataTypeSize();
                      bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcUDINT) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                    bytes.add(tempBytes[2]);
                    bytes.add(tempBytes[3]);
                } else if(plcValue instanceof PlcDWORD) {
                    if(bytes == null) {
                      fieldDataTypeSize = ModbusDataType.valueOf("DWORD").getDataTypeSize();
                      bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcDWORD) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                    bytes.add(tempBytes[2]);
                    bytes.add(tempBytes[3]);
                } else if(plcValue instanceof PlcLINT) {
                    if(bytes == null) {
                      fieldDataTypeSize = ModbusDataType.valueOf("LINT").getDataTypeSize();
                      bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcLINT) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                    bytes.add(tempBytes[2]);
                    bytes.add(tempBytes[3]);
                    bytes.add(tempBytes[4]);
                    bytes.add(tempBytes[5]);
                    bytes.add(tempBytes[6]);
                    bytes.add(tempBytes[7]);
                } else if(plcValue instanceof PlcULINT) {
                    if(bytes == null) {
                      fieldDataTypeSize = ModbusDataType.valueOf("ULINT").getDataTypeSize();
                      bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcULINT) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                    bytes.add(tempBytes[2]);
                    bytes.add(tempBytes[3]);
                    bytes.add(tempBytes[4]);
                    bytes.add(tempBytes[5]);
                    bytes.add(tempBytes[6]);
                    bytes.add(tempBytes[7]);
                } else if(plcValue instanceof PlcLWORD) {
                    if(bytes == null) {
                      fieldDataTypeSize = ModbusDataType.valueOf("LWORD").getDataTypeSize();
                      bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcLWORD) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                    bytes.add(tempBytes[2]);
                    bytes.add(tempBytes[3]);
                    bytes.add(tempBytes[4]);
                    bytes.add(tempBytes[5]);
                    bytes.add(tempBytes[6]);
                    bytes.add(tempBytes[7]);
                } else if(plcValue instanceof PlcREAL) {
                    if(bytes == null) {
                        fieldDataTypeSize = ModbusDataType.valueOf("REAL").getDataTypeSize();
                        bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcREAL) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                    bytes.add(tempBytes[2]);
                    bytes.add(tempBytes[3]);
                } else if(plcValue instanceof PlcLREAL) {
                    if(bytes == null) {
                      fieldDataTypeSize = ModbusDataType.valueOf("LREAL").getDataTypeSize();
                      bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcLREAL) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                    bytes.add(tempBytes[2]);
                    bytes.add(tempBytes[3]);
                    bytes.add(tempBytes[4]);
                    bytes.add(tempBytes[5]);
                    bytes.add(tempBytes[6]);
                    bytes.add(tempBytes[7]);
                } else if((plcValue instanceof PlcCHAR) && (plcList.getLength() % 2 == 0)) {
                    if(bytes == null) {
                        fieldDataTypeSize = ModbusDataType.valueOf("CHAR").getDataTypeSize();
                        bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcCHAR) value).getBytes();
                    bytes.add(tempBytes[0]);
                } else if((plcValue instanceof PlcWCHAR) && (plcList.getLength() % 2 == 0)) {
                    if(bytes == null) {
                        fieldDataTypeSize = ModbusDataType.valueOf("WCHAR").getDataTypeSize();
                        bytes = new ArrayList<>(plcList.getList().size() * Math.round(fieldDataTypeSize));
                    }
                    byte[] tempBytes = ((PlcWCHAR) value).getBytes();
                    bytes.add(tempBytes[0]);
                    bytes.add(tempBytes[1]);
                } else {
                    throw new PlcRuntimeException("Encoder Not found for Datatype " + plcValue.getClass().getName() + " or the array length is not divisible by 2"); //Fix BH :)
                }
            }
            if(booleans != null) {
                return booleans.toByteArray();
            } else if(bytes != null) {
                byte[] retBytes = new byte[bytes.size()];
                for(int i = 0; i < bytes.size(); i++) {
                    retBytes[i] = bytes.get(i);
                }
                return retBytes;
            }
        } else if(plcValue instanceof PlcBOOL) {
            return ((PlcBOOL) plcValue).getBytes();
        } else if(plcValue instanceof PlcINT) {
            return ((PlcINT) plcValue).getBytes();
        } else if(plcValue instanceof PlcUINT) {
            return ((PlcUINT) plcValue).getBytes();
        } else if(plcValue instanceof PlcWORD) {
            return ((PlcWORD) plcValue).getBytes();
        } else if(plcValue instanceof PlcDINT) {
            return ((PlcDINT) plcValue).getBytes();
        } else if(plcValue instanceof PlcUDINT) {
            return ((PlcUDINT) plcValue).getBytes();
        } else if(plcValue instanceof PlcDWORD) {
            return ((PlcDWORD) plcValue).getBytes();
        } else if(plcValue instanceof PlcLINT) {
            return ((PlcLINT) plcValue).getBytes();
        } else if(plcValue instanceof PlcULINT) {
            return ((PlcULINT) plcValue).getBytes();
        } else if(plcValue instanceof PlcLWORD) {
            return ((PlcLWORD) plcValue).getBytes();
        } else if(plcValue instanceof PlcREAL) {
            return ((PlcREAL) plcValue).getBytes();
        } else if(plcValue instanceof PlcLREAL) {
            return ((PlcLREAL) plcValue).getBytes();
        } else if(plcValue instanceof PlcCHAR) {
            return ((PlcCHAR) plcValue).getBytes();
        } else if(plcValue instanceof PlcWCHAR) {
            return ((PlcWCHAR) plcValue).getBytes();
        } else {
            throw new PlcRuntimeException("Encoder Not found for Datatype " + plcValue.getClass().getName());
        }
        return new byte[0];
    }

    private PlcValue readBooleanList(int count, byte[] data) throws ParseException {
        ReadBuffer io = new ReadBuffer(data);
        if(count == 1) {
            return DataItemIO.staticParse(io, (short) 1, (short) 1);
        }
        // Make sure we read in all the bytes. Unfortunately when requesting 9 bytes
        // they are ordered like this: 8 7 6 5 4 3 2 1 | 0 0 0 0 0 0 0 9
        // Luckily it turns out that this is exactly how BitSet parses byte[]
        BitSet bits = BitSet.valueOf(data);
        List<PlcBOOL> result = new ArrayList<>(count);
        for(int i = 0; i < count; i++) {
            result.add(new PlcBOOL(bits.get(i)));
        }
        return new PlcList(result);
    }

}
