/*
 * Copyright 2015-present Open Networking Laboratory
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

package org.onosproject.provider.netconf.device.impl;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.ChassisId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.incubator.net.config.basics.ConfigException;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.MastershipRole;
import org.onosproject.net.PortNumber;
import org.onosproject.net.SparseAnnotations;
import org.onosproject.net.behaviour.PortDiscovery;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.device.DefaultDeviceDescription;
import org.onosproject.net.device.DeviceDescription;
import org.onosproject.net.device.DeviceDescriptionDiscovery;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceProvider;
import org.onosproject.net.device.DeviceProviderRegistry;
import org.onosproject.net.device.DeviceProviderService;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.PortStatisticsDiscovery;
import org.onosproject.net.key.DeviceKey;
import org.onosproject.net.key.DeviceKeyAdminService;
import org.onosproject.net.key.DeviceKeyId;
import org.onosproject.net.provider.AbstractProvider;
import org.onosproject.net.provider.ProviderId;
import org.onosproject.netconf.NetconfController;
import org.onosproject.netconf.NetconfDeviceListener;
import org.onosproject.netconf.NetconfException;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Provider which uses an NETCONF controller to detect device.
 */
@Component(immediate = true)
public class NetconfDeviceProvider extends AbstractProvider
        implements DeviceProvider {

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceProviderRegistry providerRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetconfController controller;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceKeyAdminService deviceKeyAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MastershipService mastershipService;

    protected static final String APP_NAME = "org.onosproject.netconf";
    private static final String SCHEME_NAME = "netconf";
    private static final String DEVICE_PROVIDER_PACKAGE = "org.onosproject.netconf.provider.device";
    private static final String UNKNOWN = "unknown";
    protected static final String ISNULL = "NetconfDeviceInfo is null";
    private static final String IPADDRESS = "ipaddress";
    private static final String NETCONF = "netconf";
    private static final String PORT = "port";
    private static final int CORE_POOL_SIZE = 10;
    //FIXME eventually a property
    private static final int ISREACHABLE_TIMEOUT = 2000;
    private static final int DEFAULT_POLL_FREQUENCY_SECONDS = 30;

    protected final ExecutorService executor =
            Executors.newFixedThreadPool(5, groupedThreads("onos/netconfdeviceprovider",
                                                           "device-installer-%d", log));
    protected ScheduledExecutorService connectionExecutor
            = newScheduledThreadPool(CORE_POOL_SIZE,
                                     groupedThreads("onos/netconfdeviceprovider",
                                                    "connection-executor-%d", log));

    protected DeviceProviderService providerService;
    private NetconfDeviceListener innerNodeListener = new InnerNetconfDeviceListener();
    private InternalDeviceListener deviceListener = new InternalDeviceListener();
    protected ScheduledFuture<?> scheduledTask;

    private final ConfigFactory factory =
            new ConfigFactory<ApplicationId, NetconfProviderConfig>(APP_SUBJECT_FACTORY,
                                                                    NetconfProviderConfig.class,
                                                                    "devices",
                                                                    true) {
                @Override
                public NetconfProviderConfig createConfig() {
                    return new NetconfProviderConfig();
                }
            };
    protected final NetworkConfigListener cfgListener = new InternalNetworkConfigListener();
    private ApplicationId appId;
    private boolean active;


    @Activate
    public void activate() {
        active = true;
        providerService = providerRegistry.register(this);
        appId = coreService.registerApplication(APP_NAME);
        cfgService.registerConfigFactory(factory);
        cfgService.addListener(cfgListener);
        controller.addDeviceListener(innerNodeListener);
        deviceService.addListener(deviceListener);
        executor.execute(NetconfDeviceProvider.this::connectDevices);
        scheduledTask = schedulePolling();
        log.info("Started");
    }


    @Deactivate
    public void deactivate() {
        deviceService.removeListener(deviceListener);
        active = false;
        controller.getNetconfDevices().forEach(id -> {
            deviceKeyAdminService.removeKey(DeviceKeyId.deviceKeyId(id.toString()));
            controller.disconnectDevice(id, true);
        });
        controller.removeDeviceListener(innerNodeListener);
        deviceService.removeListener(deviceListener);
        providerRegistry.unregister(this);
        providerService = null;
        cfgService.unregisterConfigFactory(factory);
        scheduledTask.cancel(true);
        executor.shutdown();
        log.info("Stopped");
    }

    public NetconfDeviceProvider() {
        super(new ProviderId(SCHEME_NAME, DEVICE_PROVIDER_PACKAGE));
    }

    // Checks connection to devices in the config file
    // every DEFAULT_POLL_FREQUENCY_SECONDS seconds.
    private ScheduledFuture schedulePolling() {
        return connectionExecutor.scheduleAtFixedRate(exceptionSafe(this::checkAndUpdateDevices),
                                                      DEFAULT_POLL_FREQUENCY_SECONDS / 10,
                                                      DEFAULT_POLL_FREQUENCY_SECONDS,
                                                      TimeUnit.SECONDS);
    }

    private Runnable exceptionSafe(Runnable runnable) {
        return new Runnable() {

            @Override
            public void run() {
                try {
                    runnable.run();
                } catch (Exception e) {
                    log.error("Unhandled Exception", e);
                }
            }
        };
    }

    @Override
    public void triggerProbe(DeviceId deviceId) {
        // TODO: This will be implemented later.
        log.info("Triggering probe on device {}", deviceId);
    }

    @Override
    public void roleChanged(DeviceId deviceId, MastershipRole newRole) {
        if (active) {
            switch (newRole) {
                case MASTER:
                    initiateConnection(deviceId, newRole);
                    log.debug("Accepting mastership role change to {} for device {}", newRole, deviceId);
                    break;
                case STANDBY:
                    controller.disconnectDevice(deviceId, false);
                    providerService.receivedRoleReply(deviceId, newRole, MastershipRole.STANDBY);
                    //else no-op
                    break;
                case NONE:
                    controller.disconnectDevice(deviceId, false);
                    providerService.receivedRoleReply(deviceId, newRole, MastershipRole.NONE);
                    break;
                default:
                    log.error("Unimplemented Mastership state : {}", newRole);

            }
        }
    }

    @Override
    public boolean isReachable(DeviceId deviceId) {
        //FIXME this is a workaround util device state is shared
        // between controller instances.
        Device device = deviceService.getDevice(deviceId);
        String ip;
        int port;
        Socket socket = null;
        if (device != null) {
            ip = device.annotations().value(IPADDRESS);
            port = Integer.parseInt(device.annotations().value(PORT));
        } else {
            String[] info = deviceId.toString().split(":");
            if (info.length == 3) {
                ip = info[1];
                port = Integer.parseInt(info[2]);
            } else {
                ip = Arrays.asList(info).stream().filter(el -> !el.equals(info[0])
                        && !el.equals(info[info.length - 1]))
                        .reduce((t, u) -> t + ":" + u)
                        .get();
                log.debug("ip v6 {}", ip);
                port = Integer.parseInt(info[info.length - 1]);
            }
        }
        //test connection to device opening a socket to it.
        try {
            socket = new Socket(ip, port);
            log.debug("rechability of {}, {}, {}", deviceId, socket.isConnected(), !socket.isClosed());
            return socket.isConnected() && !socket.isClosed();
        } catch (IOException e) {
            log.info("Device {} is not reachable", deviceId);
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    log.debug("Test Socket failed {} ", deviceId);
                    return false;
                }
            }
        }
    }

    @Override
    public void changePortState(DeviceId deviceId, PortNumber portNumber,
                                boolean enable) {
        // TODO if required
    }

    private class InnerNetconfDeviceListener implements NetconfDeviceListener {


        @Override
        public void deviceAdded(DeviceId deviceId) {
            //no-op
            log.debug("Netconf device {} added to Netconf subController", deviceId);
        }

        @Override
        public void deviceRemoved(DeviceId deviceId) {
            Preconditions.checkNotNull(deviceId, ISNULL);

            if (deviceService.getDevice(deviceId) != null) {
                providerService.deviceDisconnected(deviceId);
                log.debug("Netconf device {} removed from Netconf subController", deviceId);
            } else {
                log.warn("Netconf device {} does not exist in the store, " +
                         "it may already have been removed", deviceId);
            }
        }
    }

    private void connectDevices() {
        NetconfProviderConfig cfg = cfgService.getConfig(appId, NetconfProviderConfig.class);
        if (cfg != null) {
            try {
                cfg.getDevicesAddresses().forEach(addr -> {
                    DeviceId deviceId = getDeviceId(addr.ip().toString(), addr.port());
                    Preconditions.checkNotNull(deviceId, ISNULL);
                    //Netconf configuration object
                    ChassisId cid = new ChassisId();
                    String ipAddress = addr.ip().toString();
                    SparseAnnotations annotations = DefaultAnnotations.builder()
                            .set(IPADDRESS, ipAddress)
                            .set(PORT, String.valueOf(addr.port()))
                            .set(AnnotationKeys.PROTOCOL, SCHEME_NAME.toUpperCase())
                            .build();
                    DeviceDescription deviceDescription = new DefaultDeviceDescription(
                            deviceId.uri(),
                            Device.Type.SWITCH,
                            UNKNOWN, UNKNOWN,
                            UNKNOWN, UNKNOWN,
                            cid, false,
                            annotations);
                    storeDeviceKey(addr, deviceId);

                    if (deviceService.getDevice(deviceId) == null) {
                        providerService.deviceConnected(deviceId, deviceDescription);
                    }
                    try {
                        checkAndUpdateDevice(deviceId, deviceDescription);
                    } catch (Exception e) {
                        log.error("Unhandled exception checking {}", deviceId, e);
                    }
                });
            } catch (ConfigException e) {
                log.error("Cannot read config error " + e);
            }
        }
    }

    private void checkAndUpdateDevice(DeviceId deviceId, DeviceDescription deviceDescription) {
        if (deviceService.getDevice(deviceId) == null) {
            log.warn("Device {} has not been added to store, " +
                             "maybe due to a problem in connectivity", deviceId);
        } else {
            boolean isReachable = isReachable(deviceId);
            if (isReachable && !deviceService.isAvailable(deviceId)) {
                Device device = deviceService.getDevice(deviceId);
                if (device.is(DeviceDescriptionDiscovery.class)) {
                    if (mastershipService.isLocalMaster(deviceId)) {
                        DeviceDescriptionDiscovery deviceDescriptionDiscovery =
                                device.as(DeviceDescriptionDiscovery.class);
                        DeviceDescription updatedDeviceDescription = deviceDescriptionDiscovery.discoverDeviceDetails();
                        if (updatedDeviceDescription != null &&
                                !descriptionEquals(device, updatedDeviceDescription)) {
                            providerService.deviceConnected(
                                    deviceId, new DefaultDeviceDescription(
                                            updatedDeviceDescription, true, updatedDeviceDescription.annotations()));
                        } else if (updatedDeviceDescription == null) {
                            providerService.deviceConnected(
                                    deviceId, new DefaultDeviceDescription(
                                            deviceDescription, true, deviceDescription.annotations()));
                        }
                        //if ports are not discovered, retry the discovery
                        if (deviceService.getPorts(deviceId).isEmpty()) {
                            discoverPorts(deviceId);
                        }
                    }
                } else {
                    log.warn("No DeviceDescriptionDiscovery behaviour for device {} " +
                                     "using DefaultDeviceDescription", deviceId);
                            providerService.deviceConnected(
                                    deviceId, new DefaultDeviceDescription(
                                    deviceDescription, true, deviceDescription.annotations()));
                }
            } else if (!isReachable && deviceService.isAvailable(deviceId)) {
                providerService.deviceDisconnected(deviceId);
            }
        }
    }

    private boolean descriptionEquals(Device device, DeviceDescription updatedDeviceDescription) {
        return Objects.equal(device.id().uri(), updatedDeviceDescription.deviceUri())
                && Objects.equal(device.type(), updatedDeviceDescription.type())
                && Objects.equal(device.manufacturer(), updatedDeviceDescription.manufacturer())
                && Objects.equal(device.hwVersion(), updatedDeviceDescription.hwVersion())
                && Objects.equal(device.swVersion(), updatedDeviceDescription.swVersion())
                && Objects.equal(device.serialNumber(), updatedDeviceDescription.serialNumber())
                && Objects.equal(device.chassisId(), updatedDeviceDescription.chassisId())
                && Objects.equal(device.annotations(), updatedDeviceDescription.annotations());
    }

    private void checkAndUpdateDevices() {
        NetconfProviderConfig cfg = cfgService.getConfig(appId, NetconfProviderConfig.class);
        if (cfg != null) {
            log.info("Checking connection to devices in configuration");
            try {
                cfg.getDevicesAddresses().forEach(addr -> {
                    DeviceId deviceId = getDeviceId(addr.ip().toString(), addr.port());
                    Preconditions.checkNotNull(deviceId, ISNULL);
                    //Netconf configuration object
                    ChassisId cid = new ChassisId();
                    String ipAddress = addr.ip().toString();
                    SparseAnnotations annotations = DefaultAnnotations.builder()
                            .set(IPADDRESS, ipAddress)
                            .set(PORT, String.valueOf(addr.port()))
                            .set(AnnotationKeys.PROTOCOL, SCHEME_NAME.toUpperCase())
                            .build();
                    DeviceDescription deviceDescription = new DefaultDeviceDescription(
                            deviceId.uri(),
                            Device.Type.SWITCH,
                            UNKNOWN, UNKNOWN,
                            UNKNOWN, UNKNOWN,
                            cid, false,
                            annotations);
                    storeDeviceKey(addr, deviceId);
                    checkAndUpdateDevice(deviceId, deviceDescription);
                });
            } catch (ConfigException e) {
                log.error("Cannot read config error " + e);
            }
        }
    }

    private void storeDeviceKey(NetconfProviderConfig.NetconfDeviceAddress addr, DeviceId deviceId) {
        if (addr.sshkey().equals("")) {
            deviceKeyAdminService.addKey(
                    DeviceKey.createDeviceKeyUsingUsernamePassword(
                            DeviceKeyId.deviceKeyId(deviceId.toString()),
                            null, addr.name(), addr.password()));
        } else {
            deviceKeyAdminService.addKey(
                    DeviceKey.createDeviceKeyUsingSshKey(
                            DeviceKeyId.deviceKeyId(deviceId.toString()),
                            null, addr.name(), addr.password(), addr.sshkey()));
        }
    }

    private void initiateConnection(DeviceId deviceId, MastershipRole newRole) {
        try {
            if (isReachable(deviceId)) {
                controller.connectDevice(deviceId);
                providerService.receivedRoleReply(deviceId, newRole, MastershipRole.MASTER);
            }
        } catch (Exception e) {
            if (deviceService.getDevice(deviceId) != null) {
                providerService.deviceDisconnected(deviceId);
            }
            deviceKeyAdminService.removeKey(DeviceKeyId.deviceKeyId(deviceId.toString()));
            throw new RuntimeException(new NetconfException(
                    "Can't connect to NETCONF " + "device on " + deviceId + ":" + deviceId, e));

        }
    }

    private void discoverPorts(DeviceId deviceId) {
        Device device = deviceService.getDevice(deviceId);
        //TODO remove when PortDiscovery is removed from master
        if (device.is(PortDiscovery.class)) {
            PortDiscovery portConfig = device.as(PortDiscovery.class);
            providerService.updatePorts(deviceId,
                                        portConfig.getPorts());
        } else if (device.is(DeviceDescriptionDiscovery.class)) {
            DeviceDescriptionDiscovery deviceDescriptionDiscovery =
                    device.as(DeviceDescriptionDiscovery.class);
            providerService.updatePorts(deviceId,
                                        deviceDescriptionDiscovery.discoverPortDetails());
        } else {
            log.warn("No portGetter behaviour for device {}", deviceId);
        }

        // Port statistics discovery
        if (device.is(PortStatisticsDiscovery.class)) {
            PortStatisticsDiscovery d =
                    device.as(PortStatisticsDiscovery.class);
            providerService.updatePortStatistics(deviceId,
                                                 d.discoverPortStatistics());
        } else {
            log.warn("No port statistics getter behaviour for device {}",
                     deviceId);
        }
    }

    /**
     * Return the DeviceId about the device containing the URI.
     *
     * @param ip   IP address
     * @param port port number
     * @return DeviceId
     */
    public DeviceId getDeviceId(String ip, int port) {
        try {
            return DeviceId.deviceId(new URI(NETCONF, ip + ":" + port, null));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Unable to build deviceID for device "
                                                       + ip + ":" + port, e);
        }
    }

    /**
     * Listener for configuration events.
     */
    private class InternalNetworkConfigListener implements NetworkConfigListener {


        @Override
        public void event(NetworkConfigEvent event) {
            executor.execute(NetconfDeviceProvider.this::connectDevices);
        }

        @Override
        public boolean isRelevant(NetworkConfigEvent event) {
            return event.configClass().equals(NetconfProviderConfig.class) &&
                    (event.type() == NetworkConfigEvent.Type.CONFIG_ADDED ||
                            event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED);
        }
    }

    /**
     * Listener for core device events.
     */
    private class InternalDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            if ((event.type() == DeviceEvent.Type.DEVICE_ADDED)) {
                executor.execute(() -> discoverPorts(event.subject().id()));
            } else if ((event.type() == DeviceEvent.Type.DEVICE_REMOVED)) {
                log.debug("removing device {}", event.subject().id());
                controller.disconnectDevice(event.subject().id(), true);
            }
        }

        @Override
        public boolean isRelevant(DeviceEvent event) {
            if (mastershipService.getMasterFor(event.subject().id()) == null) {
                return true;
            }
            return SCHEME_NAME.toUpperCase()
                    .equals(event.subject().annotations().value(AnnotationKeys.PROTOCOL)) &&
                    mastershipService.isLocalMaster(event.subject().id());
        }
    }
}
