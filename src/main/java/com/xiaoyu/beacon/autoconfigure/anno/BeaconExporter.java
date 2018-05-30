package com.xiaoyu.beacon.autoconfigure.anno;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author hongyu
 * @date 2018-02
 * @description 用在接口实现类上,相当于beacon-exporter
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BeaconExporter {

    String interfaceName() default "";
    

}
