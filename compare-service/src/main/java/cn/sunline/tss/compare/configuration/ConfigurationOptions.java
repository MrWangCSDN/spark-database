package cn.sunline.tss.compare.configuration;

import java.util.List;

public record ConfigurationOptions(List<String> states,List<EnumOption> enums,List<IgnoreOption> ignoreValues,boolean fieldMatchByName) {
    public ConfigurationOptions(List<String> states,List<EnumOption> enums,List<IgnoreOption> ignoreValues) {
        this(states,enums,ignoreValues,false);
    }
    public record EnumOption(long index,String oldValue,String newValue) {}
    public record IgnoreOption(long id,String value) {}
}
