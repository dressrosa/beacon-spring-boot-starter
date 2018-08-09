/**
 *  唯有读书,不慵不扰
 */
package com.xiaoyu.beacon.autoconfigure;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import com.xiaoyu.beacon.autoconfigure.anno.BeaconExporter;
import com.xiaoyu.beacon.autoconfigure.anno.BeaconRefer;
import com.xiaoyu.core.common.bean.BeaconPath;
import com.xiaoyu.core.common.constant.BeaconConstants;
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

    // 用来判断是否已经解析完成
    private static BeaconProtocol Beacon_Protocol = null;

    private static BeaconRegistry Beacon_Registry = null;

    /**
     * 注册bean后置处理器,在每个bean初始化都会执行
     * 
     * @return
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public BeanPostProcessor beanPostProcessor() {
        BeanPostProcessor processor = new BeanPostProcessor() {
            // 通过这里使得postProcessBeforeInitialization里的内容只会执行一次
            private AtomicBoolean isInit = new AtomicBoolean(false);

            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if (!isInit.get() && isInit.compareAndSet(false, true)) {
                    try {
                        initContext();
                        initRegistry();
                        initProviders();
                        initConsumers();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                return bean;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                return bean;
            }
        };
        return processor;

    }

    public void initContext() throws Exception {
        BeaconProtocol beaconProtocol = beaconProperties.getProtocol();
        if (beaconProtocol == null) {
            throw new Exception("Properties in beacon-protocol should not be null");
        }
        String port = beaconProtocol.getPort();
        if (StringUtil.isEmpty(beaconProtocol.getName())) {
            throw new Exception("Name cannot be null in beacon-protocol");
        }
        if (beaconProtocol.getName().equals("beacon")) {
            if (StringUtil.isEmpty(beaconProtocol.getPort())) {
                port = Integer.toString(1992);
            }
            if (NumberUtils.isNumber(port)) {
                throw new Exception("Port should be a positive integer in beacon-protocol");
            }
        }
        Context context = SpiManager.holder(Context.class).target(beaconProtocol.getName());
        context.server(Integer.valueOf(port));
        // 赋值
        Beacon_Protocol = beaconProtocol;
        this.springContext.addApplicationListener(new ApplicationListener<ApplicationEvent>() {
            @Override
            public void onApplicationEvent(ApplicationEvent event) {
                if (event instanceof ContextClosedEvent) {
                    LOG.info("Close the beacon context.");
                    try {
                        context.shutdown();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else if (event instanceof ContextRefreshedEvent) {
                    // 注册exporter
                    LOG.info("Register the beacon exporter.");
                    Registry registry = context.getRegistry();
                    final Set<BeaconPath> sets = exporterSet;
                    try {
                        for (BeaconPath p : sets) {
                            if (p.getSide() == From.SERVER) {
                                Class<?> cls = Class.forName(p.getService());
                                Object proxyBean = springContext.getBean(cls);
                                // 设置spring bean
                                if (proxyBean != null) {
                                    p.setProxy(proxyBean);
                                }
                                registry.registerService(p);
                            }
                        }
                        // 使命完成
                        exporterSet = null;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    public void initRegistry() throws Exception {
        BeaconRegistry reg = beaconProperties.getRegistry();
        String address = reg.getAddress();
        String protocol = reg.getProtocol();
        if (protocol == null) {
            protocol = "zookeeper";
        }
        if (StringUtil.isEmpty(address)) {
            throw new Exception("Address cannot be null in beacon-registry");
        }
        String[] addr = address.split(":");
        if (addr.length != 2) {
            throw new Exception("Address->" + address + " is illegal in beacon-registry");
        }
        if (!StringUtil.isIP(addr[0]) || !NumberUtils.isParsable(addr[1])) {
            throw new Exception("Address->" + address + " is illegal in beacon-registry");
        }
        if (StringUtil.isEmpty(protocol)) {
            throw new Exception("Protocol can be ignored but not empty in beacon-registry");
        }

        if (Beacon_Registry != null) {
            LOG.warn("Repeat beacon-registry,please check in beacon-registry");
            return;
        }
        Registry registry = SpiManager.holder(Registry.class).target(protocol);
        if (registry == null) {
            throw new Exception("Cannot find protocol->" + protocol + " in beacon-registry");
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
    }

    public void initProviders() throws Exception {
        Map<String, Object> proMap = this.springContext.getBeansWithAnnotation(BeaconExporter.class);
        if (proMap.isEmpty()) {
            LOG.info("No beacon-exporter find in classpath.");
            return;
        }
        BeaconProtocol beaconProtocol = this.beaconProperties.getProtocol();
        Iterator<Entry<String, Object>> beanEntryIter = proMap.entrySet().iterator();
        while (beanEntryIter.hasNext()) {
            Entry<String, Object> entry = beanEntryIter.next();
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            BeaconExporter anno = this.springContext.findAnnotationOnBean(beanName, BeaconExporter.class);
            if (StringUtil.isEmpty(anno.interfaceName())) {
                throw new Exception("InterfaceName cannot be null in beacon-provider");
            }
            String refName = bean.getClass().getName();
            String methods = null;
            if (StringUtil.isNotEmpty(anno.methods())) {
                if (anno.methods().contains("&")) {
                    throw new Exception("Methods contain the illegal character '&' in beacon-provider");
                }
                methods = anno.methods();
            } else {
                // 取所有的方法
                Method[] mes = bean.getClass().getDeclaredMethods();
                StringBuilder namesBuilder = new StringBuilder();
                for (int i = 0; i < mes.length - 1; i++) {
                    namesBuilder.append(mes[i].getName()).append(",");
                }
                namesBuilder.append(mes[mes.length - 1].getName());
                methods = namesBuilder.toString();
            }
            try {
                // 注册服务
                BeaconPath beaconPath = new BeaconPath();
                beaconPath
                        .setSide(From.SERVER)
                        .setService(anno.interfaceName())
                        .setRef(refName)
                        .setHost(NetUtil.localIP())
                        .setMethods(methods)
                        .setGroup(anno.group().trim());
                beaconPath.setPort(beaconProtocol.getPort());
                exporterSet.add(beaconPath);
            } catch (Exception e) {
                LOG.error("Cannot resolve exporter,please check in {}", refName);
                return;
            }
        }
        // 有provider暴漏,则启动context,相当于启动nettyServer
        Context context = SpiManager.defaultSpiExtender(Context.class);
        context.start();
    }

    public void initConsumers() {
        Map<String, Object> conMap = this.springContext.getBeansWithAnnotation(BeaconRefer.class);
        if (conMap.isEmpty()) {
            LOG.info("No beacon-reference find in classpath");
            return;
        }
        BeaconRegistry beaconReg = beaconProperties.getRegistry();
        Map<String, BeanDefinition> beanMap = new HashMap<>(16);
        Iterator<Object> iter = conMap.values().iterator();
        while (iter.hasNext()) {
            BeaconReferConfiguration config = (BeaconReferConfiguration) iter.next();
            List<BeaconReference> refers = config.beaconReference();
            if (refers == null) {
                return;
            }
            for (BeaconReference r : refers) {
                try {
                    if (StringUtil.isEmpty(r.getInterfaceName())) {
                        throw new Exception("InterfaceName cannot be null in beacon-consumer");
                    }
                    if (StringUtil.isEmpty(r.getTimeout())) {
                        r.setTimeout(BeaconConstants.REQUEST_TIMEOUT);
                    }
                    if (StringUtil.isEmpty(r.getGroup())) {
                        r.setGroup("");
                    }
                    // 注册服务
                    BeaconPath beaconPath = new BeaconPath();
                    beaconPath
                            .setSide(From.CLIENT)
                            .setService(r.getInterfaceName())
                            .setHost(NetUtil.localIP())
                            .setTimeout(r.getTimeout())
                            .setRetry(r.getRetry())
                            .setCheck(r.getCheck())
                            .setTolerant(r.getTolerant())
                            .setGroup(r.getGroup());

                    Class<?> target = Class.forName(r.getInterfaceName());
                    String beanName = StringUtil.lowerFirstChar(target.getSimpleName());
                    if (this.springContext.containsBeanDefinition(beanName)) {
                        LOG.warn("Repeat register,please check in beacon-reference with interface->{}",
                                r.getInterfaceName());
                        return;
                    }
                    Registry registry = SpiManager.holder(Registry.class).target(beaconReg.getProtocol());
                    registry.registerService(beaconPath);
                    BeanDefinition facDef = this.generateFactoryBean(target, registry);
                    beanMap.put(beanName, facDef);

                } catch (Exception e) {
                    LOG.error("Cannot resolve reference,please check in beacon-reference with interface->{}",
                            r.getInterfaceName());
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
