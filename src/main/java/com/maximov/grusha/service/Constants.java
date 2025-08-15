package com.maximov.grusha.service;

import lombok.Getter;

public class Constants {
    @Getter
    private static final String YES_BUTTON = "YES_BUTTON";
    @Getter
    private static final String NO_BUTTON = "NO_BUTTON";
    @Getter
    private static final String ADMIN_ID_NIKOLAY = "5559129467";
    @Getter
    private static final String ADMIN_ID_MARYA = "810737452";
    @Getter
    private static final java.time.format.DateTimeFormatter DATE_INPUT =
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-uuuu");
    @Getter
    private static final java.time.format.DateTimeFormatter DATE_TIME_OUTPUT =
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm");



}
