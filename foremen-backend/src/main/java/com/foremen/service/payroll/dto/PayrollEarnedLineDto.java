package com.foremen.service.payroll.dto;

import java.math.BigDecimal;

import com.foremen.service.payroll.LinePaymentStatus;

/**
 * FOR-07-06 (Requirement 11.2; design §DTO sketches) — one report line inside an earned project row
 * of a worker card month block.
 *
 * @param workReportRoomId the {@code work_report_rooms} id
 * @param workItemName     the work item name (ru → pl → code)
 * @param roomName         the room label / room type name
 * @param unit             the work item's measurement unit name
 * @param unitCost         the unit service cost; {@code null} when unavailable
 * @param quantity         the reported quantity
 * @param cost             the line cost (scale 2); {@code null} when unavailable
 * @param paidAmount       Σ allocations on the line
 * @param paymentStatus    the line payment status
 */
public record PayrollEarnedLineDto(Long workReportRoomId, String workItemName, String roomName, String unit,
                                   BigDecimal unitCost, BigDecimal quantity, BigDecimal cost, BigDecimal paidAmount,
                                   LinePaymentStatus paymentStatus) {
}
