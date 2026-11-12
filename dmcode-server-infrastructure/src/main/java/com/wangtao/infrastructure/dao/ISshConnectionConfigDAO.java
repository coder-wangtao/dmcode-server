package com.wangtao.infrastructure.dao;

import com.wangtao.infrastructure.dao.po.SshConnectionConfigPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * SSH连接高级配置DAO
 *
 * @author waissh dev
 */
@Mapper
public interface ISshConnectionConfigDAO {

    void insertOrUpdate(SshConnectionConfigPO po);

    SshConnectionConfigPO queryByConnectionId(String connectionId);

}
