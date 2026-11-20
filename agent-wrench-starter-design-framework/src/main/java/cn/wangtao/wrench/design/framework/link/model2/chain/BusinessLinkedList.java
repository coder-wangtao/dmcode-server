package cn.wangtao.wrench.design.framework.link.model2.chain;

import cn.wangtao.wrench.design.framework.link.model2.DynamicContext;
import cn.wangtao.wrench.design.framework.link.model2.handler.ILogicHandler;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 业务链路
 * @create 2025-01-18 10:27
 */
public class BusinessLinkedList<T, D extends DynamicContext, R> extends LinkedList<ILogicHandler<T, D, R>> implements ILogicHandler<T, D, R> {

    public BusinessLinkedList(String name) {
        super(name);
    }

    @Override
    public R apply(T requestParameter, D dynamicContext) throws Exception {
        Node<ILogicHandler<T, D, R>> current = this.first;
        do {
            ILogicHandler<T, D, R> item = current.item;
            try {
                // 1. 前置调用
                R applyBefore = item.applyBefore(requestParameter, dynamicContext);
                if (!dynamicContext.isProceed()) {
                    item.applyAfter(requestParameter, dynamicContext, applyBefore);
                    return applyBefore;
                }

                // 2. 节点跳过
                if (dynamicContext.isJump()) {
                    current = current.next;
                    continue;
                }

                // 3. 执行节点
                R apply = item.apply(requestParameter, dynamicContext);
                if (!dynamicContext.isProceed()) {
                    item.applyAfter(requestParameter, dynamicContext, apply);
                    return apply;
                }

                current = current.next;

            } catch (Exception e) {
                item.applyAfterException(requestParameter, dynamicContext, e);
                throw e;
            }

        } while (null != current);

        throw new RuntimeException("current item dynamic proceed is error");
    }

}
