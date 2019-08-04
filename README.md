# beacon-spring-boot-starter  
#### 用于beacon兼容spring boot

## 使用方法:
### 1. 在启动类加上注解 **@EnableBeacon**

```
@SpringBootApplication
@ComponentScan(basePackages = { "com.xiaoyu.test" })
@EnableBeacon
public class TestApplication {

    public static void main(String args[]) {
        ConfigurableApplicationContext context = null;
        try {
            context = SpringApplication.run(TestApplication.class);
            System.out.println(context.getBean(IHelloService.class).hello("lan"));
        } finally {
            context.stop();
            context.close();
        }
    }
}
```


### 2. provider端:
在接口实现类上加 **@BeaconExporter** 注解

```
@Service
@BeaconExporter(interfaceName="com.xiaoyu.test.api.IHelloService")
public class HelloServiceImpl implements IHelloService {

    @Override
    public String hello(String name) {
        try {
            TimeUnit.MILLISECONDS.sleep(new Random().nextInt(1000));//
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return "hello " + name;
    }

    @Override
    public String name(String name) {
        return "you are " + name;
    }

    @Override
    public void sing(String song) {
        System.out.println("唱歌:" + song);

    }

}
```


### 3. consumer端:
新增一个类并继承 **BeaconReferConfiguration**类,加上注解 **@BeaconRefer**,返回所有rpc相关的接口

```
@BeaconRefer
public class TestBeaconRefer extends BeaconReferConfiguration {

    @Override
    protected List<BeaconReference> doFindBeaconRefers() {
        List<BeaconReference> list = new ArrayList<>();
        list.add(new BeaconReference().setInterfaceName(IHelloService.class.getName()));
        return list;
    }

}
```
### more example:
[beacon-test-springboot](https://github.com/dressrosa/beacon-test-springboot)


