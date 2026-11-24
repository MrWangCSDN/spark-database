package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiExceptionHandler;
import cn.sunline.tss.compare.metadata.MetadataDbType;
import cn.sunline.tss.compare.metadata.MetadataSqlDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigurationNameSearchApiTest {
    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void searchesBeyondTheFirstUnfilteredPageWithMatchingCountAndStablePages(MetadataDbType type) throws Exception {
        var fixture=fixture(type);
        for(int id=1;id<=20;id++) insert(fixture,id,"unrelated-"+id,"2");
        // Deliberately insert matches out of order, beyond the first unfiltered page.
        insert(fixture,80,"CUSTOMER-X","2");
        insert(fixture,21,"Cust_账户","2");
        insert(fixture,45,"customer","2");
        insert(fixture,99,"customer file","1");
        insert(fixture,100,"unrelated","2");
        fixture.jdbc().update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_MODULE='customer',BCOMP_MEMO='customer' WHERE BCOMP_INDEX=100");

        fixture.mvc().perform(get("/api/configurations").param("keyword","  cUsT  ").param("size","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.items[*].bcompIndex").value(contains(21,45)));
        fixture.mvc().perform(get("/api/configurations").param("keyword","CUST").param("page","1").param("size","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.items[*].bcompIndex").value(contains(80)));
        fixture.mvc().perform(get("/api/configurations").param("keyword","cust").param("page","5").param("size","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.page").value(5)).andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void matchesChineseNamesAndDoesNotSearchOtherColumnsOrConfigurationTypes(MetadataDbType type) throws Exception {
        var fixture=fixture(type);
        insert(fixture,7,"借记账户余额","2");insert(fixture,3,"账户明细","2");
        insert(fixture,1,"账户文件","1");insert(fixture,2,"账户未分类",null);
        insert(fixture,8,"unrelated","2");insert(fixture,9,null,"2");
        fixture.jdbc().update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_MODULE='账户',BCOMP_MEMO='账户' WHERE BCOMP_INDEX=8");

        matches(fixture,"账户",List.of(3,7));
        matches(fixture,"余额",List.of(7));
        matches(fixture,"文件",List.of());
        matches(fixture,"missing",List.of());
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void treatsWildcardsEscapesAndSqlQuotesAsLiteralNameCharacters(MetadataDbType type) throws Exception {
        var fixture=fixture(type);
        String[] names={"rate%value","rateXvalue","some_name","someXname","bang!field","bangfield",
            "O'Brien","OBrien","literal!_%'tail","injection ' OR 1=1 --","unrelated"};
        for(int index=0;index<names.length;index++) insert(fixture,index+1,names[index],"2");
        insert(fixture,99,"hidden!_%'file","1");

        matches(fixture,"%",List.of(1,9));
        matches(fixture,"_",List.of(3,9));
        matches(fixture,"!",List.of(5,9));
        matches(fixture,"'",List.of(7,9,10));
        matches(fixture,"!_%'",List.of(9));
        matches(fixture,"' OR 1=1 --",List.of(10));
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void omittedAndBlankKeywordsKeepTheExistingListAndJavaEntryPoints(MetadataDbType type) throws Exception {
        var fixture=fixture(type);
        insert(fixture,5,"third","2");insert(fixture,1,"first","2");insert(fixture,3,null,"2");
        insert(fixture,2,"file","1");

        fixture.mvc().perform(get("/api/configurations"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.items[*].bcompIndex").value(contains(1,3,5)))
            .andExpect(jsonPath("$.items[0].name").value("first"))
            .andExpect(jsonPath("$.items[0].maxPartitionNum").value(64));
        for(String keyword:new String[]{""," \t\r\n "," ".repeat(101)}) {
            fixture.mvc().perform(get("/api/configurations").param("keyword",keyword))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].bcompIndex").value(contains(1,3,5)));
        }
        assertThat(fixture.service().list(0,20).items()).extracting(BasicConfiguration::bcompIndex).containsExactly(1L,3L,5L);
        assertThat(fixture.repository().list(1,2).items()).extracting(BasicConfiguration::bcompIndex).containsExactly(5L);
        assertThat(fixture.repository().list(1,2).total()).isEqualTo(3);
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void enforcesTheTrimmedKeywordLengthAndRetainsPaginationValidation(MetadataDbType type) throws Exception {
        var fixture=fixture(type);
        insert(fixture,1,"x".repeat(100),"2");insert(fixture,2,"other","2");

        for(String keyword:new String[]{"x".repeat(101),"  "+"中".repeat(101)+"  "}) {
            fixture.mvc().perform(get("/api/configurations").param("keyword",keyword))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIGURATION_QUERY"))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.traceId").isNotEmpty());
        }
        matches(fixture,"  "+"x".repeat(100)+"  ",List.of(1));
        for(String[] page:new String[][]{{"-1","20"},{"0","0"},{"0","101"}}) {
            fixture.mvc().perform(get("/api/configurations").param("keyword","other").param("page",page[0]).param("size",page[1]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        }
        fixture.mvc().perform(get("/api/configurations").param("keyword","other").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.size").value(1));
    }

    private static void matches(Fixture fixture,String keyword,List<Integer> ids) throws Exception {
        fixture.mvc().perform(get("/api/configurations").param("keyword",keyword).param("size","100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(ids.size()))
            .andExpect(jsonPath("$.items[*].bcompIndex").value(equalTo(ids)));
    }
    private static void insert(Fixture fixture,int id,String name,String type) {
        fixture.jdbc().update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE) VALUES(?,?,?)",id,name,type);
    }
    private static Fixture fixture(MetadataDbType type) {
        String mode=type==MetadataDbType.GDB?"MySQL":"PostgreSQL";
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE="+mode+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
        var repository=new ConfigurationRepository(source,30,MetadataSqlDialect.forType(type));
        var service=new ConfigurationService(repository,()->{});
        var mvc=MockMvcBuilders.standaloneSetup(new ConfigurationController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        return new Fixture(new JdbcTemplate(source),repository,service,mvc);
    }
    private record Fixture(JdbcTemplate jdbc,ConfigurationRepository repository,ConfigurationService service,MockMvc mvc) {}
}
