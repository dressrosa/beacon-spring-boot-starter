/**
 * 
 */
package com.xiaoyu.beacon.autoconfigure;

import com.xiaoyu.beacon.autoconfigure.anno.BeaconRefer;
import com.xiaoyu.spring.config.BeaconReference;

/**
 * @author hongyu
 * @date 2018-05-28 16:55
 * @description 搭配{@linkplain BeaconRefer}使用,用户子类需要继承此类,重写doFindBeaconRefers方法,来指定rpc接口
 */
@BeaconRefer
public abstract class BeaconReferConfiguration {

    public final BeaconReference[] beaconReference() {
        return this.doFindBeaconRefers();
    }

    /**
     * 需要重写,指定哪些接口是rpc接口
     * 
     * @return
     */
    protected abstract BeaconReference[] doFindBeaconRefers();
}
