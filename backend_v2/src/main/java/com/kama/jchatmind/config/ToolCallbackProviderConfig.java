package com.kama.jchatmind.config;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class ToolCallbackProviderConfig {

    @Bean("externalToolCallbackProvider")
    ToolCallbackProvider externalToolCallbackProvider(
            ConfigurableListableBeanFactory beanFactory
    ) {
        String[] providerBeanNames = beanFactory.getBeanNamesForType(ToolCallbackProvider.class, false, false);
        return () -> Arrays.stream(providerBeanNames)
                .filter(beanName -> !beanName.equals("mcpToolCallbackProvider"))
                .filter(beanName -> !beanName.equals("externalToolCallbackProvider"))
                .map(beanName -> beanFactory.getBean(beanName, ToolCallbackProvider.class))
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
    }
}
