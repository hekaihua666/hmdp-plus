package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Admin;

/** 管理员基础查询，由现有MapperScan扫描，不重复配置数据源。 */
public interface AdminMapper extends BaseMapper<Admin> { }
