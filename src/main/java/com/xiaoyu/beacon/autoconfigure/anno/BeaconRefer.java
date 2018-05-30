/**
 *  唯有读书,不慵不扰
 */
package com.xiaoyu.beacon.autoconfigure.anno;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Configuration;

import com.xiaoyu.beacon.autoconfigure.BeaconReferConfiguration;

/**
 * @author hongyu
 * @date 2018-02
 * @description 起标记作用,与{@linkplain BeaconReferConfiguration}搭配,用户子类需要继承BeaconReferConfiguration,并加上此注解
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@Configuration
public @interface BeaconRefer {

}
