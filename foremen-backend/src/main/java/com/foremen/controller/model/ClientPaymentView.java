package com.foremen.controller.model;

import com.foremen.service.clientpayment.ClientPaymentKind;
import com.foremen.service.clientpayment.DisplayStatus;

/**
 * FOR-11-06 (design 2, "Audience DTO selection"): the common supertype the controller returns for a
 * client-payment read; the concrete record is the staff or the client-safe DTO.
 */
public interface ClientPaymentView {
    Long id();

    ClientPaymentKind kind();

    DisplayStatus status();
}
