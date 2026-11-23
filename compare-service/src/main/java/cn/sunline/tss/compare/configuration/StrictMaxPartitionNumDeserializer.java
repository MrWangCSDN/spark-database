package cn.sunline.tss.compare.configuration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Rejects JSON coercions that could silently change a table's resource limit. */
public final class StrictMaxPartitionNumDeserializer extends StdDeserializer<Integer> {
    public StrictMaxPartitionNumDeserializer() { super(Integer.class); }

    @Override public Integer deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) throw MaxPartitionNum.invalid();
        try { return MaxPartitionNum.effective(parser.getBigIntegerValue().intValueExact()); }
        catch(ArithmeticException error) { throw MaxPartitionNum.invalid(); }
    }
}
