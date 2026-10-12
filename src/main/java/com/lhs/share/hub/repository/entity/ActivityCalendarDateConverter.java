package com.lhs.share.hub.repository.entity;

import java.time.LocalDate;
import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;
import org.springframework.lang.Nullable;

/** Stores date-only properties as ISO strings, including null query criteria. */
public class ActivityCalendarDateConverter implements MongoValueConverter<LocalDate, String> {
    @Override
    @Nullable
    public LocalDate read(@Nullable String value, MongoConversionContext context) {
        return value == null ? null : LocalDate.parse(value);
    }

    // QueryMapper passes null to write, despite the interface's non-null declaration.
    @Override
    @Nullable
    public String write(@Nullable LocalDate value, MongoConversionContext context) {
        return value == null ? null : value.toString();
    }
}
