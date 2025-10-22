package com.calai.backend.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Converter(autoApply = false)
public class LocalTimeStringConverter implements AttributeConverter<LocalTime, String> {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    @Override public String convertToDatabaseColumn(LocalTime t) { return t == null ? null : HM.format(t); }
    @Override public LocalTime convertToEntityAttribute(String s) { return (s == null || s.isBlank()) ? null : LocalTime.parse(s, HM); }
}
