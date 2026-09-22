package cn.sunline.tss.compare.batch;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/** Prevents a fractional or textual identifier from selecting a different configuration. */
public final class StrictConfigurationIdDeserializer extends StdDeserializer<Long> {
    public StrictConfigurationIdDeserializer() { super(Long.class); }

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return context.reportInputMismatch(Long.class, "配置编号必须为JSON整数");
        }
        // Jackson's integral accessor rejects values outside the signed 64-bit range.
        return parser.getLongValue();
    }
}
