/**
 * Copyright (c) 2014,2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.smarthome.core.thing.internal;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.ConfigDescription;
import org.eclipse.smarthome.config.core.ConfigDescriptionParameter;
import org.eclipse.smarthome.config.core.ConfigDescriptionParameter.Type;
import org.eclipse.smarthome.config.core.ConfigDescriptionParameterBuilder;
import org.eclipse.smarthome.config.core.ConfigDescriptionProvider;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.common.SafeCaller;
import org.eclipse.smarthome.core.items.ItemRegistry;
import org.eclipse.smarthome.core.service.ReadyMarker;
import org.eclipse.smarthome.core.service.ReadyService;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ManagedThingProvider;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerCallback;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingTypeProvider;
import org.eclipse.smarthome.core.thing.binding.builder.BridgeBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.thing.link.ItemChannelLink;
import org.eclipse.smarthome.core.thing.link.ItemChannelLinkRegistry;
import org.eclipse.smarthome.core.thing.link.ManagedItemChannelLinkProvider;
import org.eclipse.smarthome.core.thing.type.ChannelDefinition;
import org.eclipse.smarthome.core.thing.type.ChannelType;
import org.eclipse.smarthome.core.thing.type.ChannelTypeBuilder;
import org.eclipse.smarthome.core.thing.type.ChannelTypeProvider;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.thing.type.ThingType;
import org.eclipse.smarthome.core.thing.type.ThingTypeBuilder;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.test.java.JavaOSGiTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;

/**
 * OSGi tests for {@link ThingManager}.
 *
 * @author Simon Kaufmann - Initial contribution
 */
public class ThingManagerOSGiJavaTest extends JavaOSGiTest {

    private ManagedThingProvider managedThingProvider;
    private ItemRegistry itemRegistry;
    private ReadyService readyService;
    private ItemChannelLinkRegistry itemChannelLinkRegistry;

    private static final String CONFIG_PARAM_NAME = "test";
    private static final ChannelTypeUID CHANNEL_TYPE_UID = new ChannelTypeUID("binding", "channel");
    private static final ThingTypeUID THING_TYPE_UID = new ThingTypeUID("binding:thing");
    private static final ThingUID THING_UID = new ThingUID(THING_TYPE_UID, "thing");

    private static final ThingTypeUID BRIDGE_TYPE_UID = new ThingTypeUID("binding:bridge");
    private static final ThingUID BRIDGE_UID = new ThingUID(BRIDGE_TYPE_UID, "bridge");

    private static final ChannelUID CHANNEL_UID = new ChannelUID(THING_UID, "channel");

    private Thing THING;
    private URI CONFIG_DESCRIPTION_THING;
    private URI CONFIG_DESCRIPTION_CHANNEL;

    @Before
    public void setUp() throws Exception {
        CONFIG_DESCRIPTION_THING = new URI("test:test");
        CONFIG_DESCRIPTION_CHANNEL = new URI("test:channel");
        THING = ThingBuilder.create(THING_TYPE_UID, THING_UID).withChannels(Collections.singletonList( //
                ChannelBuilder.create(CHANNEL_UID, "Switch").withType(CHANNEL_TYPE_UID).build() //
        )).build();
        registerVolatileStorageService();

        configureAutoLinking(false);

        managedThingProvider = getService(ManagedThingProvider.class);

        itemRegistry = getService(ItemRegistry.class);
        assertNotNull(itemRegistry);

        itemChannelLinkRegistry = getService(ItemChannelLinkRegistry.class);
        assertNotNull(itemChannelLinkRegistry);

        readyService = getService(ReadyService.class);
        assertNotNull(readyService);

        waitForAssert(() -> {
            try {
                assertThat(
                        bundleContext.getServiceReferences(ReadyMarker.class,
                                "(esh.xmlThingTypes=" + bundleContext.getBundle().getSymbolicName() + ")"),
                        is(notNullValue()));
            } catch (InvalidSyntaxException e) {
                throw new RuntimeException(e);
            }
        });
        waitForAssert(() -> {
            try {
                assertThat(bundleContext.getServiceReferences(ChannelItemProvider.class, null), is(notNullValue()));
            } catch (InvalidSyntaxException e) {
                throw new RuntimeException(e);
            }
        });

    }

    @After
    public void teardown() throws Exception {
        managedThingProvider.getAll().forEach(it -> {
            managedThingProvider.remove(it.getUID());
        });
        configureAutoLinking(true);
    }

    @Test
    public void testInitializeCallsThingUpdated() throws Exception {
        registerThingTypeProvider();
        AtomicReference<ThingHandlerCallback> thc = new AtomicReference<>();
        AtomicReference<Boolean> initializeRunning = new AtomicReference<>(false);
        registerThingHandlerFactory(THING_TYPE_UID, thing -> {
            ThingHandler mockHandler = mock(ThingHandler.class);
            doAnswer(a -> {
                thc.set((ThingHandlerCallback) a.getArguments()[0]);
                return null;
            }).when(mockHandler).setCallback(ArgumentMatchers.isA(ThingHandlerCallback.class));
            doAnswer(a -> {
                initializeRunning.set(true);

                // call thingUpdated() from within initialize()
                thc.get().thingUpdated(THING);

                // hang on a little to provoke a potential dead-lock
                Thread.sleep(1000);

                initializeRunning.set(false);
                return null;
            }).when(mockHandler).initialize();
            when(mockHandler.getThing()).thenReturn(THING);
            return mockHandler;
        });

        new Thread((Runnable) () -> managedThingProvider.add(THING)).start();

        waitForAssert(() -> {
            assertThat(THING.getStatus(), is(ThingStatus.INITIALIZING));
        });

        // ensure it didn't run into a dead-lock which gets resolved by the SafeCaller.
        waitForAssert(() -> {
            assertThat(initializeRunning.get(), is(false));
        }, SafeCaller.DEFAULT_TIMEOUT - 100, 50);
    }

    @Test
    public void testThatIsLinkedReturnsFalse() throws Exception {
        AtomicReference<ThingHandlerCallback> thc = initializeThingHandlerCallback();

        assertFalse(thc.get().isChannelLinked(CHANNEL_UID));
    }

    @Test
    public void testThatIsLinkedReturnsTrue() throws Exception {
        AtomicReference<ThingHandlerCallback> thc = initializeThingHandlerCallback();

        ManagedItemChannelLinkProvider managedItemChannelLinkProvider = getService(
                ManagedItemChannelLinkProvider.class);
        assertNotNull(managedItemChannelLinkProvider);

        managedItemChannelLinkProvider.add(new ItemChannelLink("item", CHANNEL_UID));

        assertTrue(thc.get().isChannelLinked(CHANNEL_UID));
    }

    @Test
    public void testCreateChannelBuilder() throws Exception {
        AtomicReference<ThingHandlerCallback> thc = initializeThingHandlerCallback();

        ChannelBuilder channelBuilder = thc.get().createChannelBuilder(new ChannelUID(THING_UID, "test"),
                CHANNEL_TYPE_UID);
        Channel channel = channelBuilder.build();

        assertThat(channel.getLabel(), is("Test Label"));
        assertThat(channel.getDescription(), is("Test Description"));
        assertThat(channel.getAcceptedItemType(), is("Switch"));
        assertThat(channel.getDefaultTags().size(), is(1));
        assertThat(channel.getDefaultTags().iterator().next(), is("Test Tag"));
    }

    @Test
    public void testEditChannelBuilder() throws Exception {
        AtomicReference<ThingHandlerCallback> thc = initializeThingHandlerCallback();

        ChannelBuilder channelBuilder = thc.get().editChannel(THING, CHANNEL_UID);
        Channel channel = channelBuilder.build();

        assertNull(channel.getLabel());
        assertNull(channel.getDescription());
        assertEquals("Switch", channel.getAcceptedItemType());
        assertEquals(CHANNEL_TYPE_UID, channel.getChannelTypeUID());
    }

    @Test
    public void testInitializeOnlyIfInitializable() throws Exception {
        registerThingTypeProvider();
        registerChannelTypeProvider();
        registerThingHandlerFactory(THING_TYPE_UID, thing -> new BaseThingHandler(thing) {
            @Override
            public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
            }
        });

        ConfigDescriptionProvider mockConfigDescriptionProvider = mock(ConfigDescriptionProvider.class);
        List<ConfigDescriptionParameter> parameters = Collections.singletonList( //
                ConfigDescriptionParameterBuilder.create(CONFIG_PARAM_NAME, Type.TEXT).withRequired(true).build() //
        );
        registerService(mockConfigDescriptionProvider, ConfigDescriptionProvider.class.getName());

        // verify a missing mandatory thing config prevents it from getting initialized
        when(mockConfigDescriptionProvider.getConfigDescription(eq(CONFIG_DESCRIPTION_THING), any()))
                .thenReturn(new ConfigDescription(CONFIG_DESCRIPTION_THING, parameters));
        assertThingStatus(Collections.emptyMap(), Collections.emptyMap(), ThingStatus.UNINITIALIZED,
                ThingStatusDetail.HANDLER_CONFIGURATION_PENDING);

        // verify a missing mandatory channel config prevents it from getting initialized
        when(mockConfigDescriptionProvider.getConfigDescription(eq(CONFIG_DESCRIPTION_CHANNEL), any()))
                .thenReturn(new ConfigDescription(CONFIG_DESCRIPTION_CHANNEL, parameters));
        assertThingStatus(Collections.singletonMap(CONFIG_PARAM_NAME, "value"), Collections.emptyMap(),
                ThingStatus.UNINITIALIZED, ThingStatusDetail.HANDLER_CONFIGURATION_PENDING);

        // verify a satisfied config does not prevent it from getting initialized anymore
        assertThingStatus(Collections.singletonMap(CONFIG_PARAM_NAME, "value"),
                Collections.singletonMap(CONFIG_PARAM_NAME, "value"), ThingStatus.ONLINE, ThingStatusDetail.NONE);
    }

    @Test
    public void testChildHandlerInitialized_replacedUnitializedThing() {
        Semaphore childHandlerInitializedSemaphore = new Semaphore(1);
        Semaphore thingUpdatedSemapthore = new Semaphore(1);

        registerThingHandlerFactory(BRIDGE_TYPE_UID, bridge -> new BaseBridgeHandler((Bridge) bridge) {
            @Override
            public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
            }

            @Override
            public void initialize() {
                updateStatus(ThingStatus.ONLINE);
            }

            @Override
            public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
                try {
                    childHandlerInitializedSemaphore.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
        });
        registerThingHandlerFactory(THING_TYPE_UID, thing -> new BaseThingHandler(thing) {
            @Override
            public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
            }

            @Override
            public void initialize() {
                if (getBridge() == null) {
                    throw new RuntimeException("Fail because of missing bridge");
                }
                updateStatus(ThingStatus.ONLINE);
            }

            @Override
            public void thingUpdated(Thing thing) {
                this.thing = thing;
                try {
                    thingUpdatedSemapthore.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
        });

        Bridge bridge = BridgeBuilder.create(BRIDGE_TYPE_UID, BRIDGE_UID).build();
        managedThingProvider.add(bridge);
        waitForAssert(() -> {
            assertEquals(ThingStatus.ONLINE, bridge.getStatus());
        });

        Thing thing = ThingBuilder.create(THING_TYPE_UID, THING_UID).build();
        managedThingProvider.add(thing);
        waitForAssert(() -> {
            assertEquals(ThingStatus.UNINITIALIZED, thing.getStatus());
            assertEquals(ThingStatusDetail.HANDLER_INITIALIZING_ERROR, thing.getStatusInfo().getStatusDetail());
        });

        assertEquals(1, childHandlerInitializedSemaphore.availablePermits());

        Thing thing2 = ThingBuilder.create(THING_TYPE_UID, THING_UID).withBridge(BRIDGE_UID).build();
        managedThingProvider.update(thing2);
        waitForAssert(() -> {
            assertEquals(ThingStatus.ONLINE, thing2.getStatus());
        });

        // childHandlerInitialized(...) must be called
        waitForAssert(() -> assertEquals(0, childHandlerInitializedSemaphore.availablePermits()));

        // thingUpdated(...) is not called
        assertEquals(1, thingUpdatedSemapthore.availablePermits());
    }

    @Test
    public void testChildHandlerInitialized_modifiedUninitializedThing() {
        Semaphore childHandlerInitializedSemaphore = new Semaphore(1);
        Semaphore thingUpdatedSemapthore = new Semaphore(1);

        registerThingHandlerFactory(BRIDGE_TYPE_UID, bridge -> new BaseBridgeHandler((Bridge) bridge) {
            @Override
            public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
            }

            @Override
            public void initialize() {
                updateStatus(ThingStatus.ONLINE);
            }

            @Override
            public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
                try {
                    childHandlerInitializedSemaphore.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
        });
        registerThingHandlerFactory(THING_TYPE_UID, thing -> new BaseThingHandler(thing) {
            @Override
            public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
            }

            @Override
            public void initialize() {
                if (getBridge() == null) {
                    throw new RuntimeException("Fail because of missing bridge");
                }
                updateStatus(ThingStatus.ONLINE);
            }

            @Override
            public void thingUpdated(Thing thing) {
                this.thing = thing;
                try {
                    thingUpdatedSemapthore.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
        });

        Bridge bridge = BridgeBuilder.create(BRIDGE_TYPE_UID, BRIDGE_UID).build();
        managedThingProvider.add(bridge);
        waitForAssert(() -> {
            assertEquals(ThingStatus.ONLINE, bridge.getStatus());
        });

        Thing thing = ThingBuilder.create(THING_TYPE_UID, THING_UID).build();
        managedThingProvider.add(thing);
        waitForAssert(() -> {
            assertEquals(ThingStatus.UNINITIALIZED, thing.getStatus());
            assertEquals(ThingStatusDetail.HANDLER_INITIALIZING_ERROR, thing.getStatusInfo().getStatusDetail());
        });

        assertEquals(1, childHandlerInitializedSemaphore.availablePermits());

        thing.setBridgeUID(bridge.getUID());
        managedThingProvider.update(thing);
        waitForAssert(() -> {
            assertEquals(ThingStatus.ONLINE, thing.getStatus());
        });

        // childHandlerInitialized(...) must be called
        waitForAssert(() -> assertEquals(0, childHandlerInitializedSemaphore.availablePermits()));

        // thingUpdated(...) is not called
        assertEquals(1, thingUpdatedSemapthore.availablePermits());
    }

    @Test
    public void testChildHandlerInitialized_replacedInitializedThing() {
        Semaphore childHandlerInitializedSemaphore = new Semaphore(1);
        Semaphore thingUpdatedSemapthore = new Semaphore(1);

        registerThingHandlerFactory(BRIDGE_TYPE_UID, bridge -> new BaseBridgeHandler((Bridge) bridge) {
            @Override
            public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
            }

            @Override
            public void initialize() {
                updateStatus(ThingStatus.ONLINE);
            }

            @Override
            public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
                try {
                    childHandlerInitializedSemaphore.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
        });
        registerThingHandlerFactory(THING_TYPE_UID, thing -> new BaseThingHandler(thing) {
            @Override
            public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
            }

            @Override
            public void initialize() {
                updateStatus(ThingStatus.ONLINE);
            }

            @Override
            public void thingUpdated(Thing thing) {
                this.thing = thing;
                try {
                    thingUpdatedSemapthore.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
        });

        Bridge bridge = BridgeBuilder.create(BRIDGE_TYPE_UID, BRIDGE_UID).build();
        managedThingProvider.add(bridge);
        waitForAssert(() -> {
            assertEquals(ThingStatus.ONLINE, bridge.getStatus());
        });

        Thing thing = ThingBuilder.create(THING_TYPE_UID, THING_UID).build();
        managedThingProvider.add(thing);
        waitForAssert(() -> {
            assertEquals(ThingStatus.ONLINE, thing.getStatus());
        });

        assertEquals(1, childHandlerInitializedSemaphore.availablePermits());

        Thing thing2 = ThingBuilder.create(THING_TYPE_UID, THING_UID).withBridge(BRIDGE_UID).build();
        managedThingProvider.update(thing2);
        waitForAssert(() -> {
            assertEquals(ThingStatus.ONLINE, thing2.getStatus());
        });

        // childHandlerInitialized(...) is not be called - framework calls ThingHandler.thingUpdated(...) instead.
        assertEquals(1, childHandlerInitializedSemaphore.availablePermits());

        // ThingHandler.thingUpdated(...) must be called
        assertEquals(0, thingUpdatedSemapthore.availablePermits());
    }

    private void assertThingStatus(Map<String, Object> propsThing, Map<String, Object> propsChannel, ThingStatus status,
            ThingStatusDetail statusDetail) {
        Configuration configThing = new Configuration(propsThing);
        Configuration configChannel = new Configuration(propsChannel);

        Thing thing = ThingBuilder.create(THING_TYPE_UID, THING_UID).withChannels(Collections.singletonList( //
                ChannelBuilder.create(CHANNEL_UID, "Switch").withType(CHANNEL_TYPE_UID).withConfiguration(configChannel)
                        .build() //
        )).withConfiguration(configThing).build();

        managedThingProvider.add(thing);

        waitForAssert(() -> {
            assertEquals(status, thing.getStatus());
            assertEquals(statusDetail, thing.getStatusInfo().getStatusDetail());
        });

        managedThingProvider.remove(thing.getUID());
    }

    private void registerThingHandlerFactory(ThingTypeUID thingTypeUID,
            Function<Thing, ThingHandler> thingHandlerProducer) {
        ComponentContext context = mock(ComponentContext.class);
        when(context.getBundleContext()).thenReturn(bundleContext);

        TestThingHandlerFactory mockThingHandlerFactory = new TestThingHandlerFactory(thingTypeUID,
                thingHandlerProducer);
        mockThingHandlerFactory.activate(context);
        registerService(mockThingHandlerFactory, ThingHandlerFactory.class.getName());
    }

    private void registerThingTypeProvider() throws Exception {
        ThingType thingType = ThingTypeBuilder.instance(THING_TYPE_UID, "label")
                .withConfigDescriptionURI(CONFIG_DESCRIPTION_THING)
                .withChannelDefinitions(Collections.singletonList(new ChannelDefinition("channel", CHANNEL_TYPE_UID)))
                .build();

        ThingTypeProvider mockThingTypeProvider = mock(ThingTypeProvider.class);
        when(mockThingTypeProvider.getThingType(eq(THING_TYPE_UID), any())).thenReturn(thingType);
        registerService(mockThingTypeProvider);
    }

    private void registerChannelTypeProvider() throws Exception {
        ChannelType channelType = ChannelTypeBuilder.state(CHANNEL_TYPE_UID, "Test Label", "Switch")
                .withDescription("Test Description").withCategory("Test Category").withTag("Test Tag")
                .withConfigDescriptionURI(new URI("test:channel")).build();

        ChannelTypeProvider mockChannelTypeProvider = mock(ChannelTypeProvider.class);
        when(mockChannelTypeProvider.getChannelType(eq(CHANNEL_TYPE_UID), any())).thenReturn(channelType);
        registerService(mockChannelTypeProvider);
    }

    private void configureAutoLinking(Boolean on) throws IOException {
        ConfigurationAdmin configAdmin = getService(ConfigurationAdmin.class);
        org.osgi.service.cm.Configuration config = configAdmin.getConfiguration("org.eclipse.smarthome.links", null);
        Dictionary<String, Object> properties = config.getProperties();
        if (properties == null) {
            properties = new Hashtable<>();
        }
        properties.put("autoLinks", on.toString());
        config.update(properties);
    }

    private static class TestThingHandlerFactory extends BaseThingHandlerFactory {

        private final ThingTypeUID thingTypeUID;
        private final Function<Thing, ThingHandler> thingHandlerProducer;

        public TestThingHandlerFactory(ThingTypeUID thingTypeUID, Function<Thing, ThingHandler> thingHandlerProducer) {
            this.thingTypeUID = thingTypeUID;
            this.thingHandlerProducer = thingHandlerProducer;
        }

        @Override
        public void activate(ComponentContext context) {
            super.activate(context);
        }

        @Override
        public boolean supportsThingType(@NonNull ThingTypeUID thingTypeUID) {
            return this.thingTypeUID.equals(thingTypeUID);
        }

        @Override
        protected @Nullable ThingHandler createHandler(@NonNull Thing thing) {
            return thingHandlerProducer.apply(thing);
        }
    };

    private AtomicReference<ThingHandlerCallback> initializeThingHandlerCallback() throws Exception {
        registerThingTypeProvider();
        registerChannelTypeProvider();
        AtomicReference<ThingHandlerCallback> thc = new AtomicReference<>();
        ThingHandlerFactory thingHandlerFactory = new BaseThingHandlerFactory() {
            @Override
            public boolean supportsThingType(@NonNull ThingTypeUID thingTypeUID) {
                return true;
            }

            @Override
            protected @Nullable ThingHandler createHandler(@NonNull Thing thing) {
                ThingHandler mockHandler = mock(ThingHandler.class);
                doAnswer(a -> {
                    thc.set((ThingHandlerCallback) a.getArguments()[0]);
                    return null;
                }).when(mockHandler).setCallback(any(ThingHandlerCallback.class));
                when(mockHandler.getThing()).thenReturn(THING);
                return mockHandler;
            }
        };
        registerService(thingHandlerFactory, ThingHandlerFactory.class.getName());
        new Thread((Runnable) () -> managedThingProvider.add(THING)).start();

        waitForAssert(() -> {
            assertNotNull(thc.get());
        });
        return thc;
    }
}
