package org.apache.plc4x.java.isotp.netty.model.tpdus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.plc4x.java.isotp.netty.model.params.Parameter;
import org.apache.plc4x.java.isotp.netty.model.types.DisconnectReason;
import org.apache.plc4x.java.isotp.netty.model.types.ProtocolClass;
import org.apache.plc4x.java.isotp.netty.model.types.RejectCause;
import org.apache.plc4x.java.isotp.netty.model.types.TpduCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IsotpModelTests {

    @Test
    @Tag("Fast")
    void errorTpdu() {
        short destinationReference = 0x1;
        RejectCause rejectCause = RejectCause.REASON_NOT_SPECIFIED;
        List<Parameter> parameters = Collections.emptyList();
        ByteBuf userData = Unpooled.buffer();

        userData.writeByte(0x7F);

        ErrorTpdu tpdu = new ErrorTpdu(destinationReference, rejectCause, parameters, userData);

        assertTrue(tpdu.getTpduCode() == TpduCode.TPDU_ERROR);
        assertTrue(tpdu.getDestinationReference() == 0x1, "Unexpected destination reference");
        assertTrue(tpdu.getRejectCause() == RejectCause.REASON_NOT_SPECIFIED);
        assertTrue(tpdu.getParameters().isEmpty(), "Unexpected parameters");
        assertTrue(tpdu.getUserData().readByte() == (byte) 0x7F, "Unexpected user data");
    }

    @Test
    @Tag("Fast")
    void dataTpdu() {
        short destinationReference = 0x1;
               List<Parameter> parameters = Collections.emptyList();
        ByteBuf userData = Unpooled.buffer();

        userData.writeByte(0x66);

        DataTpdu tpdu = new DataTpdu(true, (byte) 0x7F, parameters, userData);

        assertTrue(tpdu.getTpduCode() == TpduCode.DATA);
        assertTrue(tpdu.isEot() == true, "Unexpected eot reference");
        assertTrue(tpdu.getTpduRef() == 0x7F);
        assertTrue(tpdu.getParameters().isEmpty(), "Unexpected parameters");
        assertTrue(tpdu.getUserData().readByte() == (byte) 0x66, "Unexpected user data");
    }

    @Test
    @Tag("Fast")
    void connectionRequestTpdu() {
        short destinationReference = 0x1;
        short sourceReference = 0x2;
        ProtocolClass protocolClass = ProtocolClass.CLASS_0;
        List<Parameter> parameters = Collections.emptyList();
        ByteBuf userData = Unpooled.buffer();

        userData.writeByte(0x33);

        ConnectionRequestTpdu tpdu = new ConnectionRequestTpdu(destinationReference, sourceReference, protocolClass, parameters, userData);

        assertTrue(tpdu.getTpduCode() == TpduCode.CONNECTION_REQUEST);
        assertTrue(tpdu.getDestinationReference() == 0x1, "Unexpected destination reference");
        assertTrue(tpdu.getSourceReference() == 0x2, "Unexpected source reference");
        assertTrue(tpdu.getProtocolClass() == ProtocolClass.CLASS_0);
        assertTrue(tpdu.getParameters().isEmpty(), "Unexpected parameters");
        assertTrue(tpdu.getUserData().readByte() == (byte) 0x33, "Unexpected user data");
    }

    @Test
    @Tag("Fast")
    void connectionConfirmTpdu() {
        short destinationReference = 0x3;
        short sourceReference = 0x4;
        ProtocolClass protocolClass = ProtocolClass.CLASS_1;
        List<Parameter> parameters = Collections.emptyList();
        ByteBuf userData = Unpooled.buffer();

        userData.writeByte(0x44);

        ConnectionConfirmTpdu tpdu = new ConnectionConfirmTpdu(destinationReference, sourceReference, protocolClass, parameters, userData);

        assertTrue(tpdu.getTpduCode() == TpduCode.CONNECTION_CONFIRM);
        assertTrue(tpdu.getDestinationReference() == 0x3, "Unexpected destination reference");
        assertTrue(tpdu.getSourceReference() == 0x4, "Unexpected source reference");
        assertTrue(tpdu.getProtocolClass() == ProtocolClass.CLASS_1);
        assertTrue(tpdu.getParameters().isEmpty(), "Unexpected parameters");
        assertTrue(tpdu.getUserData().readByte() == (byte) 0x44, "Unexpected user data");
    }

    @Test
    @Tag("Fast")
    void disconnectionRequestTpdu() {
        short destinationReference = 0x1;
        short sourceReference = 0x2;
        DisconnectReason disconnectReason = DisconnectReason.ADDRESS_UNKNOWN;
        List<Parameter> parameters = Collections.emptyList();
        ByteBuf userData = Unpooled.buffer();

        userData.writeByte(0x22);

        DisconnectRequestTpdu tpdu = new DisconnectRequestTpdu(destinationReference, sourceReference, disconnectReason, parameters, userData);

        assertTrue(tpdu.getTpduCode() == TpduCode.DISCONNECT_REQUEST);
        assertTrue(tpdu.getDestinationReference() == 0x1, "Unexpected destination reference");
        assertTrue(tpdu.getSourceReference() == 0x2, "Unexpected source reference");
        assertTrue(tpdu.getDisconnectReason() == DisconnectReason.ADDRESS_UNKNOWN);
        assertTrue(tpdu.getParameters().isEmpty(), "Unexpected parameters");
        assertTrue(tpdu.getUserData().readByte() == (byte) 0x22, "Unexpected user data");
    }

    @Test
    @Tag("Fast")
    void disconnectionConfirmTpdu() {
        short destinationReference = 0x3;
        short sourceReference = 0x4;
        List<Parameter> parameters = Collections.emptyList();
        ByteBuf userData = Unpooled.buffer();

        userData.writeByte(0x11);

        DisconnectConfirmTpdu tpdu = new DisconnectConfirmTpdu(destinationReference, sourceReference, parameters, userData);

        assertTrue(tpdu.getTpduCode() == TpduCode.DISCONNECT_CONFIRM);
        assertTrue(tpdu.getDestinationReference() == 0x3, "Unexpected destination reference");
        assertTrue(tpdu.getSourceReference() == 0x4, "Unexpected source reference");
        assertTrue(tpdu.getParameters().isEmpty(), "Unexpected parameters");
        assertTrue(tpdu.getUserData().readByte() == (byte) 0x11, "Unexpected user data");
    }

}