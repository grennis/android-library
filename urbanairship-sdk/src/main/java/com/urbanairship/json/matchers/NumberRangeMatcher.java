/* Copyright 2018 Urban Airship and Contributors */

package com.urbanairship.json.matchers;

import android.support.annotation.NonNull;
import android.support.annotation.RestrictTo;

import com.urbanairship.json.JsonMap;
import com.urbanairship.json.JsonValue;
import com.urbanairship.json.ValueMatcher;

/**
 * Range matcher.
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class NumberRangeMatcher extends ValueMatcher {

    public static final String MIN_VALUE_KEY = "at_least";
    public static final String MAX_VALUE_KEY = "at_most";

    private final Double min;
    private final Double max;

    /**
     * Default constructor.
     *
     * @param min The min value.
     * @param max The max value.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public NumberRangeMatcher(Double min, Double max) {
        this.min = min;
        this.max = max;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        NumberRangeMatcher that = (NumberRangeMatcher) o;

        if (min != null ? !min.equals(that.min) : that.min != null) {
            return false;
        }
        return max != null ? max.equals(that.max) : that.max == null;
    }

    @Override
    public int hashCode() {
        int result = min != null ? min.hashCode() : 0;
        result = 31 * result + (max != null ? max.hashCode() : 0);
        return result;
    }

    @Override
    protected boolean apply(@NonNull JsonValue value) {
        if (min != null && (!value.isNumber() || value.getNumber().doubleValue() < min)) {
            return false;
        }

        if (max != null && (!value.isNumber() || value.getNumber().doubleValue() > max)) {
            return false;
        }

        return true;
    }

    @Override
    public JsonValue toJsonValue() {
        return JsonMap.newBuilder()
                      .putOpt(MIN_VALUE_KEY, min)
                      .putOpt(MAX_VALUE_KEY, max)
                      .build()
                      .toJsonValue();
    }
}
