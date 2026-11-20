package cn.wangtao.wrench.design.framework.link.model1;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 抽象类
 * @create 2025-01-18 09:14
 */
public abstract class AbstractLogicLink<T, D, R> implements ILogicLink<T, D, R> {

    private ILogicLink<T, D, R> next;

    // （重载）
    @Override
    public ILogicLink<T, D, R> next() {
        return next;
    }

    @Override
    public ILogicLink<T, D, R> appendNext(ILogicLink<T, D, R> next) {
        this.next = next;
        return next;
    }
    // （重载）
    protected R next(T requestParameter, D dynamicContext) throws Exception {
        return next.apply(requestParameter, dynamicContext);
    }

}
