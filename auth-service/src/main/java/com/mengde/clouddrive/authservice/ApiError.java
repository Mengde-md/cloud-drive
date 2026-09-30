package com.mengde.clouddrive.authservice;

/**
 * 与单体 com.mengde.clouddrive.web.ApiError 逐字段相同。
 *
 * 为什么不抽公共模块：抽 common jar 会立刻产生"两个部署单元共享一个可变依赖"的耦合，
 * 改一个字段要同时发布三个工件。在只有一个 record、三个字段的规模下，复制的成本远低于
 * 共享的成本。这条判断写在这里，是为了让未来想抽公共模块的人看见代价而不是看见疏忽。
 */
public record ApiError(int status, String error, String message) { }
