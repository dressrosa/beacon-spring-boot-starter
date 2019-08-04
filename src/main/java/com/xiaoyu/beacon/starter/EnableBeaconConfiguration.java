/**
 *  唯有读书,不慵不扰
 */
package com.xiaoyu.beacon.starter;

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

import com.xiaoyu.beacon.common.bean.BeaconPath;
import com.xiaoyu.beacon.common.constant.BeaconConstants;
import com.xiaoyu.beacon.common.constant.From;
import com.xiaoyu.beacon.common.extension.SpiManager;
import com.xiaoyu.beacon.common.utils.BeaconUtil;
import com.xiaoyu.beacon.common.utils.NetUtil;
import com.xiaoyu.beacon.common.utils.StringUtil;
import com.xiaoyu.beacon.registry.Registry;
import com.xiaoyu.beacon.rpc.api.Context;
import com.xiaoyu.beacon.spring.config.BeaconFactoryBean;
import com.xiaoyu.beacon.spring.config.BeaconProtocol;
import com.xiaoyu.beacon.spring.config.BeaconReference;
import com.xiaoyu.beacon.spring.config.BeaconRegistry;
import com.xiaoyu.beacon.starter.anno.BeaconExporter;
import com.xiaoyu.beacon.starter.anno.BeaconRefer;

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
    private static Set<BeaconPath> Lazy_Exporter_Set = new HashSet<>();

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
                        initConsumers();
                        initProviders();
                    } catch (Exception e) {
                        LOG.error("" + e);
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
        if ("beacon".equals(beaconProtocol.getName())) {
            if (StringUtil.isEmpty(beaconProtocol.getPort())) {
                port = Integer.toString(BeaconConstants.PORT);
            }
            if (!NumberUtils.isNumber(port)) {
                throw new Exception("Port should be a positive integer in beacon-protocol");
            }
        }
        Context context = SpiManager.holder(Context.class).target(beaconProtocol.getName());
        context.port(Integer.valueOf(port));
        context.server();
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
                        LOG.error("" + e);
                    }
                } else if (event instanceof ContextRefreshedEvent) {
                    // 注册exporter
                    LOG.info("Register the beacon exporter.");
                    Registry registry = context.getRegistry();
                    final Set<BeaconPath> sets = Lazy_Exporter_Set;
                    try {
                        for (BeaconPath p : sets) {
                            if (p.getSide() != From.SERVER) {
                                continue;
                            }
                            Class<?> cls = Class.forName(p.getRef());
                            Map<String, ?> proxyBeans = springContext.getBeansOfType(cls, true, true);
                            // TODO
                            if (proxyBeans.isEmpty()) {
                                String key = StringUtil.lowerFirstChar(cls.getSimpleName());
                                if (springContext.containsBean(key)) {
                                    p.setProxy(BeaconUtil.getOriginBean(springContext.getBean(key)));
                                } else {
                                    throw new Exception(
                                            "Cannot find spring bean with name '" + cls.getName() + "'");
                                }
                            } else {
                                // 设置spring bean
                                Iterator<?> iter = proxyBeans.values().iterator();
                                if (proxyBeans.size() == 1) {
                                    p.setProxy(iter.next());
                                } else {
                                    while (iter.hasNext()) {
                                        Object bean = iter.next();
                                        if (cls.isInstance(bean)) {
                                            p.setProxy(bean);
                                            break;
                                        }
                                    }
                                }
                            }
                            registry.registerService(p);
                        }
                        // 使命完成
                        Lazy_Exporter_Set = null;
                    } catch (Exception e) {
                        LOG.error("" + e);
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
        // 多个bean可能对应的是同一个代理类,这里用来过滤
        Set<String> filter = new HashSet<>();
        Iterator<Entry<String, Object>> beanEntryIter = proMap.entrySet().iterator();
        while (beanEntryIter.hasNext()) {
            Entry<String, Object> entry = beanEntryIter.next();
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            String beanStr = bean.toString();
            if (filter.contains(beanStr)) {
                continue;
            }
            BeaconExporter anno = this.springContext.findAnnotationOnBean(beanName, BeaconExporter.class);
            if (StringUtil.isEmpty(anno.interfaceName())) {
                throw new Exception("InterfaceName cannot be null in beacon-provider");
            }
            // spring bean需要取到原生的class (com.xiaoyu.xxxServiceImpl@5eabff6b)
            String refName = beanStr.substring(0, beanStr.indexOf("@"));
            String methods = null;
            if (StringUtil.isNotEmpty(anno.methods())) {
                if (anno.methods().contains("&")) {
                    throw new Exception("Methods contain the illegal character '&' in beacon-provider");
                }
                methods = anno.methods();
            } else {
                // 取所有的方法
                Method[] mes = Class.forName(anno.interfaceName()).getDeclaredMethods();
                StringBuilder namesBuilder = new StringBuilder();
                int len = mes.length - 1;
                for (int i = 0; i < len; i++) {
                    namesBuilder.append(mes[i].getName()).append(",");
                }
                namesBuilder.append(mes[len].getName());
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
                        .setGroup(anno.group().trim())
                        .setDowngrade("")
                        .setTolerant("");
                beaconPath.setPort(beaconProtocol.getPort());
                Lazy_Exporter_Set.add(beaconPath);
            } catch (Exception e) {
                LOG.error("Cannot resolve exporter,please check in {}", refName);
                return;
            }
            filter.add(beanStr);
        }
        // 有provider暴漏,则启动context,相当于启动nettyServer
        Context context = SpiManager.defaultSpiExtender(Context.class);
        context.start();
    }

    public void initConsumers() throws Exception {
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
                if (StringUtil.isEmpty(r.getInterfaceName())) {
                    throw new Exception("InterfaceName cannot be null in beacon-consumer");
                }
                if (StringUtil.isEmpty(r.getTimeout())) {
                    r.setTimeout(BeaconConstants.REQUEST_TIMEOUT);
                }
                if (StringUtil.isBlank(r.getGroup())) {
                    r.setGroup("");
                }
                if (StringUtil.isBlank(r.getTolerant())) {
                    r.setTolerant(BeaconConstants.TOLERANT_FAILFAST);
                }
                if (StringUtil.isBlank(r.getDowngrade())) {
                    r.setDowngrade("");
                } else {
                    String[] arr = r.getDowngrade().split(":");
                    if (arr.length < 2) {
                        throw new Exception(
                                "Cannot resolve reference in beacon-reference with downgrade:" + r.getDowngrade());
                    }
                    if (!("limit".equals(arr[0]) || "fault".equals(arr[0]) || "timeout".equals(arr[0]))
                            || !StringUtil.isNumeric(arr[1])) {
                        throw new Exception(
                                "Cannot resolve reference in beacon-reference with wrong downgrade strategy ["
                                        + r.getDowngrade() + "]");
                    }
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
                        .setGroup(r.getGroup())
                        .setDowngrade(r.getDowngrade());

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
            }
        }

        // 注册bean
        Iterator<Entry<String, BeanDefinition>> beanIter = beanMap.entrySet().iterator();
        while (beanIter.hasNext()) {
            Entry<String, BeanDefinition> en = beanIter.next();
            this.springContext.registerBeanDefinition(en.getKey(), en.getValue());
        }
    }

    private BeanDefinition generateFactoryBean(Class<?> target, Registry registry) throws Exception {
        GenericBeanDefinition facDef = new GenericBeanDefinition();
        facDef.setBeanClass(BeaconFactoryBean.class);
        facDef.getPropertyValues().add("target", target);
        facDef.getPropertyValues().add("registry", registry);
        facDef.setLazyInit(false);
        facDef.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
        return facDef;
    }

}
