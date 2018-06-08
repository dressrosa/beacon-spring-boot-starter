/**
 *  唯有读书,不慵不扰
 */
package com.xiaoyu.beacon.autoconfigure;

import com.xiaoyu.beacon.autoconfigure.anno.BeaconRefer;
import com.xiaoyu.spring.config.BeaconReference;

/**
 * @author hongyu
 * @date 2018-05-28 16:55
 * @description 搭配注解{@linkplain BeaconRefer}使用,用户子类需要继承此类,重写doFindBeaconRefers方法,来指定rpc接口,
 *              例如:
 * 
 *              <pre>
 * {@code 
 * @BeaconRefer
 * public class BeaconReferTest extends BeaconReferConfiguration {
 *
 *     protected BeaconReference[] doFindBeaconRefers() {
 *         List<BeaconReference> list = new ArrayList<>();
 *         list.add(new BeaconReference().setInterfaceName(IHelloService.class.getName()));
 *       return list.toArray(new BeaconReference[] {});
 *   }
 * }}
 *              </pre>
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
