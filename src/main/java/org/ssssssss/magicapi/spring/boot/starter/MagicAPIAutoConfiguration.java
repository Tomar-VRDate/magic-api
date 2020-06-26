package org.ssssssss.magicapi.spring.boot.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.ssssssss.magicapi.cache.DefaultSqlCache;
import org.ssssssss.magicapi.cache.SqlCache;
import org.ssssssss.magicapi.config.DynamicDataSource;
import org.ssssssss.magicapi.config.RequestExecutor;
import org.ssssssss.magicapi.config.RequestInterceptor;
import org.ssssssss.magicapi.config.WebUIController;
import org.ssssssss.magicapi.provider.PageProvider;
import org.ssssssss.magicapi.provider.impl.DefaultPageProvider;
import org.ssssssss.script.MagicScriptEngine;
import org.ssssssss.script.functions.DatabaseQuery;

import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

@Configuration
@ConditionalOnClass({DataSource.class, RequestMappingHandlerMapping.class})
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(MagicAPIProperties.class)
public class MagicAPIAutoConfiguration implements WebMvcConfigurer {

    private MagicAPIProperties properties;

    @Autowired(required = false)
    private List<RequestInterceptor> requestInterceptors;

    @Autowired
    RequestMappingHandlerMapping requestMappingHandlerMapping;

    private static Logger logger = LoggerFactory.getLogger(MagicAPIAutoConfiguration.class);

    public MagicAPIAutoConfiguration(MagicAPIProperties properties) {
        this.properties = properties;
    }

    private String redirectIndex(HttpServletRequest request){
        if(request.getRequestURI().endsWith("/")){
            return "redirect:./index.html";
        }
        return "redirect:"+properties.getWeb()+"/index.html";
    }

    @ResponseBody
    private MagicAPIProperties readConfig(){
        return properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String web = properties.getWeb();
        if(web != null){
            // 配置静态资源路径
            registry.addResourceHandler(web + "/**").addResourceLocations("classpath:/magicapi-support/");
            try {
                // 默认首页设置
                requestMappingHandlerMapping.registerMapping(RequestMappingInfo.paths(web).build(),this,MagicAPIAutoConfiguration.class.getDeclaredMethod("redirectIndex", HttpServletRequest.class));
                // 读取配置
                requestMappingHandlerMapping.registerMapping(RequestMappingInfo.paths(web + "/config.json").build(),this,MagicAPIAutoConfiguration.class.getDeclaredMethod("readConfig"));
            } catch (NoSuchMethodException ignored) {
            }
        }
    }

    @ConditionalOnMissingBean(PageProvider.class)
    @Bean
    public PageProvider pageProvider() {
        PageConfig pageConfig = properties.getPageConfig();
        logger.info("未找到分页实现,采用默认分页实现,分页配置:(页码={},页大小={},默认首页={},默认页大小={})", pageConfig.getPage(), pageConfig.getSize(), pageConfig.getDefaultPage(), pageConfig.getDefaultSize());
        return new DefaultPageProvider(pageConfig.getPage(), pageConfig.getSize(), pageConfig.getDefaultPage(), pageConfig.getDefaultSize());
    }

    @ConditionalOnMissingBean(SqlCache.class)
    @Bean
    public SqlCache sqlCache() {
        CacheConfig cacheConfig = properties.getCacheConfig();
        return new DefaultSqlCache(cacheConfig.getCapacity(), cacheConfig.getTtl());
    }


    @Bean
    public RequestExecutor requestExecutor(DynamicDataSource dynamicDataSource,PageProvider pageProvider) {
        MagicScriptEngine.addDefaultImport("db",new DatabaseQuery(dynamicDataSource,pageProvider));
        Method[] methods = WebUIController.class.getDeclaredMethods();
        WebUIController controller = new WebUIController(dynamicDataSource.getJdbcTemplate(null),properties.getDebugConfig().getTimeout());
        if(this.properties.isBanner()){
            controller.printBanner();
        }
        String base = properties.getWeb();
        for (Method method : methods) {
            RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
            if(requestMapping != null){
                String[] paths = Stream.of(requestMapping.value()).map(value->base + value).toArray(String[]::new);
                requestMappingHandlerMapping.registerMapping(RequestMappingInfo.paths(paths).build(),controller,method);
            }
        }
        RequestExecutor requestExecutor = new RequestExecutor();
        if (this.requestInterceptors != null) {
            this.requestInterceptors.forEach(interceptor -> {
                logger.info("注册请求拦截器：{}", interceptor.getClass());
                requestExecutor.addRequestInterceptor(interceptor);
            });
        }
        return requestExecutor;
    }

    @Bean
    @ConditionalOnMissingBean(DynamicDataSource.class)
    public DynamicDataSource dynamicDataSource(DataSource dataSource) {
        DynamicDataSource dynamicDataSource = new DynamicDataSource();
        dynamicDataSource.put(dataSource);
        return dynamicDataSource;
    }
}
