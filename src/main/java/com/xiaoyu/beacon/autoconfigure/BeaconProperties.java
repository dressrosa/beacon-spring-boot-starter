/**
 * 
 */
package com.xiaoyu.beacon.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.xiaoyu.spring.config.BeaconProtocol;
import com.xiaoyu.spring.config.BeaconRegistry;

/**
 * @author hongyu
 * @date 2018-05
 * @description 注入beacon相关的配置
 */
@ConfigurationProperties(prefix = "beacon")
public class BeaconProperties {

    private BeaconRegistry registry;

    private BeaconProtocol protocol;

    public BeaconRegistry getRegistry() {
        return registry;
    }

    public void setRegistry(BeaconRegistry registry) {
        this.registry = registry;
    }

    public BeaconProtocol getProtocol() {
        return protocol;
    }

    public void setProtocol(BeaconProtocol protocol) {
        this.protocol = protocol;
    }

}
