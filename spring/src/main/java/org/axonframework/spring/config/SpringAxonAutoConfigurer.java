/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.spring.config;

import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandTargetResolver;
import org.axonframework.commandhandling.model.GenericJpaRepository;
import org.axonframework.commandhandling.model.Repository;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.lock.LockFactory;
import org.axonframework.common.lock.NullLockFactory;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.config.*;
import org.axonframework.eventhandling.ErrorHandler;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.ListenerInvocationErrorHandler;
import org.axonframework.eventhandling.saga.ResourceInjector;
import org.axonframework.eventhandling.saga.repository.SagaStore;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventsourcing.AggregateFactory;
import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.messaging.annotation.MessageHandler;
import org.axonframework.messaging.annotation.ParameterResolverFactory;
import org.axonframework.messaging.correlation.CorrelationDataProvider;
import org.axonframework.queryhandling.QueryBus;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.upcasting.event.EventUpcaster;
import org.axonframework.spring.config.annotation.SpringContextParameterResolverFactoryBuilder;
import org.axonframework.spring.eventsourcing.SpringPrototypeAggregateFactory;
import org.axonframework.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.spring.saga.SpringResourceInjector;
import org.axonframework.spring.stereotype.Aggregate;
import org.axonframework.spring.stereotype.Saga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.axonframework.common.ReflectionUtils.methodsOf;
import static org.axonframework.common.annotation.AnnotationUtils.findAnnotationAttributes;
import static org.axonframework.spring.SpringUtils.isQualifierMatch;
import static org.springframework.beans.factory.support.BeanDefinitionBuilder.genericBeanDefinition;

/**
 * ImportBeanDefinitionRegistrar implementation that sets up an infrastructure Configuration based on beans available
 * in the application context.
 * <p>
 * This component is backed by a DefaultConfiguration (see {@link DefaultConfigurer#defaultConfiguration()}
 * and registers the following beans if present in the ApplicationContext:
 * <ul>
 * <li>{@link CommandBus}</li>
 * <li>{@link EventStorageEngine} or {@link EventBus}</li>
 * <li>{@link Serializer}</li>
 * <li>{@link TokenStore}</li>
 * <li>{@link PlatformTransactionManager}</li>
 * <li>{@link TransactionManager}</li>
 * <li>{@link SagaStore}</li>
 * <li>{@link ResourceInjector} (which defaults to {@link SpringResourceInjector}</li>
 * </ul>
 * <p>
 * Furthermore, all beans with an {@link Aggregate @Aggregate} or {@link Saga @Saga} annotation are inspected and
 * required components to operate the Aggregate or Saga are registered.
 *
 * @author Allard Buijze
 * @see EnableAxon
 * @since 3.0
 */
public class SpringAxonAutoConfigurer implements ImportBeanDefinitionRegistrar, BeanFactoryAware {

    /**
     * Name of the {@link AxonConfiguration} bean.
     */
    @SuppressWarnings("WeakerAccess")
    public static final String AXON_CONFIGURATION_BEAN = "org.axonframework.spring.config.AxonConfiguration";

    /**
     * Name of the {@link Configurer} bean.
     */
    @SuppressWarnings("WeakerAccess")
    public static final String AXON_CONFIGURER_BEAN = "org.axonframework.config.Configurer";

    private static final Logger logger = LoggerFactory.getLogger(SpringAxonAutoConfigurer.class);
    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        registry.registerBeanDefinition("commandHandlerSubscriber",
                                        genericBeanDefinition(CommandHandlerSubscriber.class).getBeanDefinition());

        registry.registerBeanDefinition("queryHandlerSubscriber",
                                        genericBeanDefinition(QueryHandlerSubscriber.class).getBeanDefinition());

        Configurer configurer = DefaultConfigurer.defaultConfiguration();

        RuntimeBeanReference parameterResolver =
                SpringContextParameterResolverFactoryBuilder.getBeanReference(registry);
        configurer.registerComponent(ParameterResolverFactory.class, c -> beanFactory
                .getBean(parameterResolver.getBeanName(), ParameterResolverFactory.class));

        findComponent(CommandBus.class)
                .ifPresent(commandBus -> configurer.configureCommandBus(c -> getBean(commandBus, c)));
        findComponent(QueryBus.class)
                .ifPresent(queryBus -> configurer.configureQueryBus(c -> getBean(queryBus, c)));
        findComponent(EventStorageEngine.class)
                .ifPresent(ese -> configurer.configureEmbeddedEventStore(c -> getBean(ese, c)));
        findComponent(EventBus.class).ifPresent(eventBus -> configurer.configureEventBus(c -> getBean(eventBus, c)));
        findComponent(Serializer.class)
                .ifPresent(serializer -> configurer.configureSerializer(c -> getBean(serializer, c)));
        findComponent(Serializer.class, "eventSerializer")
                .ifPresent(eventSerializer -> configurer.configureEventSerializer(c -> getBean(eventSerializer, c)));
        findComponent(Serializer.class, "messageSerializer").ifPresent(
                messageSerializer -> configurer.configureMessageSerializer(c -> getBean(messageSerializer, c)));
        findComponent(TokenStore.class)
                .ifPresent(tokenStore -> configurer.registerComponent(TokenStore.class, c -> getBean(tokenStore, c)));
        try {
            findComponent(PlatformTransactionManager.class).ifPresent(
                    ptm -> configurer.configureTransactionManager(c -> new SpringTransactionManager(getBean(ptm, c))));
        } catch (NoClassDefFoundError error) {
            // that's fine...
        }
        findComponent(TransactionManager.class)
                .ifPresent(tm -> configurer.configureTransactionManager(c -> getBean(tm, c)));
        findComponent(SagaStore.class)
                .ifPresent(sagaStore -> configurer.registerComponent(SagaStore.class, c -> getBean(sagaStore, c)));
        findComponent(ListenerInvocationErrorHandler.class).ifPresent(
                handler -> configurer.registerComponent(ListenerInvocationErrorHandler.class, c -> getBean(handler, c))
        );
        findComponent(ErrorHandler.class).ifPresent(
                handler -> configurer.registerComponent(ErrorHandler.class, c -> getBean(handler, c))
        );

        String resourceInjector = findComponent(ResourceInjector.class, registry,
                                                () -> genericBeanDefinition(SpringResourceInjector.class)
                                                        .getBeanDefinition());
        configurer.configureResourceInjector(c -> getBean(resourceInjector, c));

        registerCorrelationDataProviders(configurer);
        registerEventUpcasters(configurer);
        registerAggregateBeanDefinitions(configurer, registry);
        registerSagaBeanDefinitions(configurer);
        registerModules(configurer);

        Optional<String> eventHandlingConfiguration = findComponent(EventHandlingConfiguration.class);
        String ehConfigBeanName = eventHandlingConfiguration.orElse("eventHandlingConfiguration");
        if (!eventHandlingConfiguration.isPresent()) {
            registry.registerBeanDefinition(ehConfigBeanName, genericBeanDefinition(EventHandlingConfiguration.class)
                    .getBeanDefinition());
        }

        beanFactory.registerSingleton(AXON_CONFIGURER_BEAN, configurer);
        registry.registerBeanDefinition(AXON_CONFIGURATION_BEAN, genericBeanDefinition(AxonConfiguration.class)
                .addConstructorArgReference(AXON_CONFIGURER_BEAN).getBeanDefinition());
        registerEventHandlerRegistrar(ehConfigBeanName, registry);
    }

    private void registerCorrelationDataProviders(Configurer configurer) {
        configurer.configureCorrelationDataProviders(
                c -> {
                    String[] correlationDataProviderBeans =
                            beanFactory.getBeanNamesForType(CorrelationDataProvider.class);
                    return Arrays.stream(correlationDataProviderBeans)
                                 .map(n -> (CorrelationDataProvider) getBean(n, c))
                                 .collect(Collectors.toList());
                });
    }

    private void registerEventUpcasters(Configurer configurer) {
        Arrays.stream(beanFactory.getBeanNamesForType(EventUpcaster.class))
              .forEach(name -> configurer.registerEventUpcaster(c -> getBean(name, c)));
    }

    @SuppressWarnings("unchecked")
    private <T> T getBean(String beanName, Configuration configuration) {
        return (T) configuration.getComponent(ApplicationContext.class).getBean(beanName);
    }

    private void registerEventHandlerRegistrar(String ehConfigBeanName, BeanDefinitionRegistry registry) {
        List<RuntimeBeanReference> beans = new ManagedList<>();
        beanFactory.getBeanNamesIterator().forEachRemaining(bean -> {
            if (!beanFactory.isFactoryBean(bean)) {
                Class<?> beanType = beanFactory.getType(bean);
                if (beanType != null && beanFactory.containsBeanDefinition(bean) &&
                        beanFactory.getBeanDefinition(bean).isSingleton()) {
                    boolean hasHandler =
                            StreamSupport.stream(methodsOf(beanType).spliterator(), false)
                                         .map(m -> findAnnotationAttributes(m, MessageHandler.class).orElse(null))
                                         .filter(Objects::nonNull)
                                         .anyMatch(attr -> EventMessage.class
                                                 .isAssignableFrom((Class) attr.get("messageType")));
                    if (hasHandler) {
                        beans.add(new RuntimeBeanReference(bean));
                    }
                }
            }
        });
        registry.registerBeanDefinition("eventHandlerRegistrar", genericBeanDefinition(EventHandlerRegistrar.class)
                .addConstructorArgReference(AXON_CONFIGURATION_BEAN).addConstructorArgReference(ehConfigBeanName)
                .addPropertyValue("eventHandlers", beans).getBeanDefinition());
    }

    private void registerModules(Configurer configurer) {
        registerConfigurerModules(configurer);
        registerModuleConfigurations(configurer);
    }

    private void registerConfigurerModules(Configurer configurer) {
        String[] configurerModules = beanFactory.getBeanNamesForType(ConfigurerModule.class);
        for (String configurerModuleBeanName : configurerModules) {
            ConfigurerModule configurerModule = beanFactory.getBean(configurerModuleBeanName, ConfigurerModule.class);
            configurerModule.configureModule(configurer);
        }
    }

    private void registerModuleConfigurations(Configurer configurer) {
        String[] moduleConfigurations = beanFactory.getBeanNamesForType(ModuleConfiguration.class);
        for (String moduleConfiguration : moduleConfigurations) {
            configurer.registerModule(new LazyRetrievedModuleConfiguration(
                    () -> beanFactory.getBean(moduleConfiguration, ModuleConfiguration.class)
            ));
        }
    }

    private void registerSagaBeanDefinitions(Configurer configurer) {
        String[] sagas = beanFactory.getBeanNamesForAnnotation(Saga.class);
        for (String saga : sagas) {
            Saga sagaAnnotation = beanFactory.findAnnotationOnBean(saga, Saga.class);
            Class<?> sagaType = beanFactory.getType(saga);
            boolean explicitSagaConfig = !"".equals(sagaAnnotation.configurationBean());
            String configName = explicitSagaConfig
                    ? sagaAnnotation.configurationBean()
                    : lcFirst(sagaType.getSimpleName()) + "Configuration";
            if (explicitSagaConfig || beanFactory.containsBean(configName)) {
                configurer.registerModule(new LazyRetrievedModuleConfiguration(
                        () -> beanFactory.getBean(configName, ModuleConfiguration.class))
                );
            } else {
                SagaConfiguration<?> sagaConfiguration =
                        SagaConfiguration.subscribingSagaManager(sagaType);
                beanFactory.registerSingleton(configName, sagaConfiguration);

                if (!"".equals(sagaAnnotation.sagaStore())) {
                    //noinspection unchecked
                    sagaConfiguration.configureSagaStore(
                            c -> beanFactory.getBean(sagaAnnotation.sagaStore(), SagaStore.class));
                }
                configurer.registerModule(sagaConfiguration);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void registerAggregateBeanDefinitions(Configurer configurer, BeanDefinitionRegistry registry) {
        String[] aggregates = beanFactory.getBeanNamesForAnnotation(Aggregate.class);
        for (String aggregate : aggregates) {
            Aggregate aggregateAnnotation = beanFactory.findAnnotationOnBean(aggregate, Aggregate.class);
            Class<?> aggregateType = beanFactory.getType(aggregate);
            AggregateConfigurer<?> aggregateConf = AggregateConfigurer.defaultConfiguration(aggregateType);
            if ("".equals(aggregateAnnotation.repository())) {
                String repositoryName = lcFirst(aggregateType.getSimpleName()) + "Repository";
                String factoryName =
                        aggregate.substring(0, 1).toLowerCase() + aggregate.substring(1) + "AggregateFactory";
                if (beanFactory.containsBean(repositoryName)) {
                    aggregateConf.configureRepository(c -> beanFactory.getBean(repositoryName, Repository.class));
                } else {
                    if (!registry.isBeanNameInUse(factoryName)) {
                        registry.registerBeanDefinition(factoryName,
                                                        genericBeanDefinition(SpringPrototypeAggregateFactory.class)
                                                                .addPropertyValue("prototypeBeanName", aggregate)
                                                                .getBeanDefinition());
                    }
                    aggregateConf
                            .configureAggregateFactory(c -> beanFactory.getBean(factoryName, AggregateFactory.class));
                    String triggerDefinition = aggregateAnnotation.snapshotTriggerDefinition();
                    if (!"".equals(triggerDefinition)) {
                        aggregateConf.configureSnapshotTrigger(
                                c -> beanFactory.getBean(triggerDefinition, SnapshotTriggerDefinition.class));
                    }
                    if (AnnotationUtils.findAnnotation(aggregateType, "javax.persistence.Entity") != null) {
                        aggregateConf.configureRepository(
                                c -> new GenericJpaRepository(
                                        c.getComponent(EntityManagerProvider.class,
                                                       () -> beanFactory.getBean(EntityManagerProvider.class)),
                                        aggregateType,
                                        c.eventBus(),
                                        c.getComponent(LockFactory.class, () -> NullLockFactory.INSTANCE),
                                        c.parameterResolverFactory()));
                    }
                }
            } else {
                aggregateConf.configureRepository(
                        c -> beanFactory.getBean(aggregateAnnotation.repository(), Repository.class));
            }

            if (!"".equals(aggregateAnnotation.commandTargetResolver())) {
                aggregateConf.configureCommandTargetResolver(c -> getBean(aggregateAnnotation.commandTargetResolver(),
                                                                          c));
            } else {
                findComponent(CommandTargetResolver.class).ifPresent(commandTargetResolver -> aggregateConf
                        .configureCommandTargetResolver(c -> getBean(commandTargetResolver, c)));
            }

            configurer.configureAggregate(aggregateConf);
        }
    }

    /**
     * Return the given {@code string}, with its first character lowercase
     *
     * @param string The input string
     * @return The input string, with first character lowercase
     */
    private String lcFirst(String string) {
        return string.substring(0, 1).toLowerCase() + string.substring(1);
    }

    private <T> String findComponent(Class<T> componentType, BeanDefinitionRegistry registry,
                                     Supplier<BeanDefinition> defaultBean) {
        return findComponent(componentType).orElseGet(() -> {
            BeanDefinition beanDefinition = defaultBean.get();
            String beanName = BeanDefinitionReaderUtils.generateBeanName(beanDefinition, registry);
            registry.registerBeanDefinition(beanName, beanDefinition);
            return beanName;
        });
    }

    private <T> Optional<String> findComponent(Class<T> componentType, String componentQualifier) {
        return Stream.of(beanFactory.getBeanNamesForType(componentType))
                     .filter(bean -> isQualifierMatch(bean, beanFactory, componentQualifier))
                     .findFirst();
    }

    private <T> Optional<String> findComponent(Class<T> componentType) {
        String[] beans = beanFactory.getBeanNamesForType(componentType);
        if (beans.length == 1) {
            return Optional.of(beans[0]);
        } else if (beans.length > 1) {
            for (String bean : beans) {
                BeanDefinition beanDef = beanFactory.getBeanDefinition(bean);
                if (beanDef.isPrimary()) {
                    return Optional.of(bean);
                }
            }
            logger.warn("Multiple beans of type {} found in application context: {}. Chose {}",
                        componentType.getSimpleName(), beans, beans[0]);
            return Optional.of(beans[0]);
        }
        return Optional.empty();
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }

    /**
     * Implementation of an {@link ImportSelector} that enables the import of the {@link SpringAxonAutoConfigurer} after
     * all {@code @Configuration} beans have been processed.
     */
    public static class ImportSelector implements DeferredImportSelector {

        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[]{SpringAxonAutoConfigurer.class.getName()};
        }
    }

    private static class LazyRetrievedModuleConfiguration implements ModuleConfiguration {

        private final Supplier<ModuleConfiguration> delegateSupplier;
        private ModuleConfiguration delegate;

        LazyRetrievedModuleConfiguration(Supplier<ModuleConfiguration> delegateSupplier) {
            this.delegateSupplier = delegateSupplier;
        }

        @Override
        public void initialize(Configuration config) {
            delegate = delegateSupplier.get();
            delegate.initialize(config);
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }
}
