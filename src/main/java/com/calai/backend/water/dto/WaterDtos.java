package com.calai.backend.water.dto;

import java.time.LocalDate;

public class WaterDtos {

    /** 回傳給 App 的喝水摘要 */
    public record WaterSummaryDto(
            LocalDate date,
            int cups,
            int ml,
            int flOz
    ) {}

    /** App POST /water/increment 時帶的 body */
    public record AdjustRequest(
            int cupsDelta
    ) {}
}
