/**
 *  唯有读书,不慵不扰
 */
package com.xiaoyu.beacon.autoconfigure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;

import com.xiaoyu.beacon.autoconfigure.anno.BeaconExporter;
import com.xiaoyu.beacon.autoconfigure.anno.BeaconRefer;
import com.xiaoyu.core.common.bean.BeaconPath;
import com.xiaoyu.core.common.constant.From;
import com.xiaoyu.core.common.extension.SpiManager;
import com.xiaoyu.core.common.utils.NetUtil;
import com.xiaoyu.core.common.utils.StringUtil;
import com.xiaoyu.core.register.Registry;
import com.xiaoyu.core.rpc.api.Context;
import com.xiaoyu.spring.config.BeaconFactoryBean;
import com.xiaoyu.spring.config.BeaconProtocol;
import com.xiaoyu.spring.config.BeaconReference;
import com.xiaoyu.spring.config.BeaconRegistry;

/**
 * @author hongyu
 * @date 2018-05
 * @description 解析protocol/registry/reference/exporter,相当于xml
 */

@Configuration
@ConditionalOnBean(annotation = EnableBeacon.class)
@EnableConfigurationProperties(BeaconProperties.class)
public class EnableBeaconConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EnableBeaconConfiguration.class);

    /**
     * 缓存exporter,等spring启动后才进行注册
     */
    private static Set<BeaconPath> exporterSet = new HashSet<>();

    @Autowired
    private GenericApplicationContext springContext;

    @Autowired
    private BeaconProperties beaconProperties;

    private static BeaconProtocol Beacon_Protocol = null;

    private static BeaconRegistry Beacon_Registry = null;

    @PostConstruct()
    public void init() {
        try {
            initContext();
            initRegistry();
            initProviders();
            initConsumers();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void initContext() throws Exception {
        BeaconProtocol beaconProtocol = beaconProperties.getProtocol();
        if (beaconProtocol == null) {
            throw new Exception("properties in beacon-protocol should not be null");
        }
        String port = beaconProtocol.getPort();
        if (StringUtil.isBlank(beaconProtocol.getName())) {
            throw new Exception("name cannot be null in beacon-protocol");
        }
        if (StringUtil.isBlank(beaconProtocol.getPort())) {
            port = Integer.toString(1992);
        }
        if (!NumberUtils.isCreatable(port)) {
            throw new Exception("port should be a positive integer in beacon-protocol");
        }
        try {
            Context context = SpiManager.holder(Context.class).target(beaconProtocol.getName());
            context.server(Integer.valueOf(port));
            // 赋值
            Beacon_Protocol = beaconProtocol;
            this.springContext.addApplicationListener(new ApplicationListener<ApplicationEvent>() {
                @Override
                public void onApplicationEvent(ApplicationEvent event) {
                    if (event instanceof ContextClosedEvent) {
                        LOG.info("close the beacon context...");
                        try {
                            context.stop();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else if (event instanceof ContextRefreshedEvent) {
                        //注册exporter
                        Registry registry = context.getRegistry();
                        final Set<BeaconPath> sets = exporterSet;
                        try {
                            for (BeaconPath p : sets) {
                                if (p.getSide() == From.SERVER) {
                                    Class<?> cls = Class.forName(p.getService());
                                    Object proxyBean = springContext.getBean(cls);
                                    //设置spring bean
                                    if (proxyBean != null) {
                                        p.setProxy(proxyBean);
                                    }
                                    registry.registerService(p);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                    }

                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
    }

    public void initRegistry() throws Exception {
        BeaconRegistry reg = beaconProperties.getRegistry();
        String address = reg.getAddress();
        String protocol = reg.getProtocol();
        if (protocol == null) {
            protocol = "zookeeper";
        }
        if (StringUtil.isBlank(address)) {
            throw new Exception("address cannot be null in beacon-registry");
        }
        String[] addr = address.split(":");
        if (addr.length != 2) {
            throw new Exception("address->" + address + " is illegal in beacon-registry");
        }
        if (!StringUtil.isIP(addr[0]) || !NumberUtils.isParsable(addr[1])) {
            throw new Exception("address->" + address + " is illegal in beacon-registry");
        }
        if (StringUtil.isBlank(protocol)) {
            throw new Exception("protocol can ignore but not empty in beacon-registry");
        }

        try {
            if (Beacon_Registry != null) {
                LOG.warn("repeat registry.please check in beacon-registry");
                return;
            }
            Registry registry = SpiManager.holder(Registry.class).target(protocol);
            if (registry == null) {
                throw new Exception("cannot find protocol->" + protocol + " in beacon-registry");
            }
            registry.address(address);
            Context context = null;
            if (Beacon_Protocol != null) {
                context = SpiManager.holder(Context.class).target(Beacon_Protocol.getName());
                context.registry(registry);
            } else {
                // client端没有beaconProtocol
                context = SpiManager.defaultSpiExtender(Context.class);
                context.registry(registry);
            }
            // 赋值
            Beacon_Registry = reg;
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
    }

    public void initProviders() throws Exception {
        Map<String, Object> proMap = this.springContext.getBeansWithAnnotation(BeaconExporter.class);
        if (proMap.isEmpty()) {
            LOG.info("no beacon-exporter find in classpath.");
            return;
        }
        BeaconProtocol beaconProtocol = this.beaconProperties.getProtocol();
        Iterator<String> beanNameIter = proMap.keySet().iterator();
        while (beanNameIter.hasNext()) {
            String beanName = beanNameIter.next();
            BeaconExporter anno = this.springContext.findAnnotationOnBean(beanName, BeaconExporter.class);
            if (StringUtil.isBlank(anno.interfaceName())) {
                throw new Exception("interfaceName cannot be null in beacon-provider");
            }
            String refName = proMap.get(beanName).getClass().getName();
            try {
                // 注册服务
                BeaconPath beaconPath = new BeaconPath();
                beaconPath
                        .setSide(From.SERVER)
                        .setService(anno.interfaceName())
                        .setRef(refName)
                        .setHost(NetUtil.localIP());
                beaconPath.setPort(beaconProtocol.getPort());
                exporterSet.add(beaconPath);
            } catch (Exception e) {
                LOG.error("cannot resolve exporter,please check in {}", refName);
                return;
            }
        }

    }

    public void initConsumers() {
        Map<String, Object> conMap = this.springContext.getBeansWithAnnotation(BeaconRefer.class);
        if (conMap.isEmpty()) {
            LOG.info("no beacon-reference find in classpath.");
            return;
        }
        BeaconRegistry beaconReg = beaconProperties.getRegistry();
        Map<String, BeanDefinition> beanMap = new HashMap<>();
        Iterator<Object> iter = conMap.values().iterator();
        while (iter.hasNext()) {
            BeaconReferConfiguration config = (BeaconReferConfiguration) iter.next();
            BeaconReference[] refers = config.beaconReference();
            for (BeaconReference r : refers) {
                try {
                    if (StringUtil.isBlank(r.getInterfaceName())) {
                        throw new Exception("interfaceName cannot be null in beacon-consumer");
                    }
                    if (StringUtil.isBlank(r.getTimeout())) {
                        r.setTimeout("3000");
                    }
                    // 注册服务
                    BeaconPath beaconPath = new BeaconPath();
                    beaconPath
                            .setSide(From.CLIENT)
                            .setService(r.getInterfaceName())
                            .setHost(NetUtil.localIP())
                            .setTimeout(r.getTimeout());

                    Class<?> target = Class.forName(r.getInterfaceName());
                    String beanName = StringUtil.lowerFirstChar(target.getSimpleName());
                    if (this.springContext.containsBeanDefinition(beanName)) {
                        LOG.warn("repeat register.please check in beacon-reference with id->{},interface->{}",
                                r.getId(),
                                r.getInterfaceName());
                        return;
                    }
                    Registry registry = SpiManager.holder(Registry.class).target(beaconReg.getProtocol());
                    registry.registerService(beaconPath);
                    BeanDefinition facDef = this.generateFactoryBean(target, registry);
                    beanMap.put(beanName, facDef);

                } catch (Exception e) {
                    LOG.error("cannot resolve reference,please check in beacon-reference with id->{},interface->{}",
                            r.getId(), r.getInterfaceName());
                    return;
                }

            }
        }

        // 注册bean
        Iterator<Entry<String, BeanDefinition>> beanIter = beanMap.entrySet().iterator();
        while (beanIter.hasNext()) {
            Entry<String, BeanDefinition> en = beanIter.next();
            this.springContext.registerBeanDefinition(en.getKey(), en.getValue());
        }
    }

    private BeanDefinition generateFactoryBean(Class<?> target, Registry registry) {
        BeaconFactoryBean fac = new BeaconFactoryBean(target, registry);
        GenericBeanDefinition facDef = new GenericBeanDefinition();
        facDef.setBeanClass(fac.getClass());
        facDef.setLazyInit(false);
        facDef.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
        // 构造函数
        ConstructorArgumentValues val = new ConstructorArgumentValues();
        val.addGenericArgumentValue(target);
        val.addGenericArgumentValue(registry);
        facDef.setConstructorArgumentValues(val);
        return facDef;
    }
}
