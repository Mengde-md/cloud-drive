package com.mengde.clouddrive.storage;

/**
 * 一次落盘结果（Day17）：对象键、真实字节数与内容 MD5。
 *
 * <p>字节数和 MD5 都由服务端在写入时顺流计算，不采信客户端上报的值：
 * 客户端可以谎报大小绕过配额，也可以谎报 MD5 把别人的文件"秒传"到自己名下。</p>
 */
public record StoredObject(String storageKey, long byteSize, String md5) {
}
