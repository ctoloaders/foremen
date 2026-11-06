package com.foremen.service.roomsimport.ai;

import com.foremen.service.roomsimport.ai.RoomsVisionException.Reason;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default wiring when {@code foremen.rooms-import.enabled} is {@code false}/absent: the application
 * starts normally; any call fails with {@link Reason#DISABLED}.
 */
@Component
@ConditionalOnProperty(prefix = "foremen.rooms-import", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class DisabledRoomsVisionClient implements RoomsVisionClient {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public DetectResponse detectRooms(DetectRequest request) {
        throw disabled();
    }

    @Override
    public MeasureResponse measureRoom(MeasureRequest request) {
        throw disabled();
    }

    private static RoomsVisionException disabled() {
        return new RoomsVisionException(Reason.DISABLED, "Rooms auto-import is disabled");
    }
}
