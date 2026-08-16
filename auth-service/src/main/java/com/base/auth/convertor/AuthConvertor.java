package com.base.auth.convertor;

import com.base.auth.entity.User;
import com.base.auth.param.RegisterParam;
import com.base.auth.vo.UserInfoVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * ========================================================================
 * 【面试知识点 —— MapStruct 转换器：认证服务专用】
 * ========================================================================
 *
 * 这个接口负责认证服务中 Entity ↔ VO / Param 的对象转换。
 *
 * ========================================================================
 * 【面试知识点 —— 转换器在架构中的位置】
 * ========================================================================
 *
 * 典型的分层数据流向：
 *
 *   前端请求（JSON）
 *     ↓  Jackson 反序列化
 *   Param（请求参数对象）          ← 入参
 *     ↓  Convertor 转换
 *   Entity（数据库实体）           ← 持久层
 *     ↓  MyBatis-Plus 查询
 *   Entity（从数据库读取）
 *     ↓  Convertor 转换
 *   VO（响应视图对象）             ← 出参
 *     ↓  Jackson 序列化
 *   前端响应（JSON）
 *
 * ========================================================================
 * 【面试知识点 —— @Mapper(componentModel = "spring") 详解】
 * ========================================================================
 *
 * componentModel = "spring" 让生成的实现类带上 @Component 注解：
 *
 * 生成的代码大致是这样的（简化版）：
 * <pre>
 * &#64;Component
 * public class AuthConvertorImpl implements AuthConvertor {
 *
 *     &#64;Override
 *     public UserInfoVO toUserInfoVO(User user) {
 *         if (user == null) return null;
 *         UserInfoVO vo = new UserInfoVO();
 *         vo.setId(user.getId());
 *         vo.setEmail(user.getEmail());
 *         vo.setNickName(user.getNickName());
 *         vo.setUseSpace(user.getUseSpace());
 *         vo.setTotalSpace(user.getTotalSpace());
 *         vo.setLastLoginTime(user.getLastLoginTime());
 *         vo.setGmtCreate(user.getGmtCreate());
 *         // passwordHash 没有映射 → 保持 null（安全！）
 *         return vo;
 *     }
 *
 *     &#64;Override
 *     public User toUser(RegisterParam param) {
 *         if (param == null) return null;
 *         User user = new User();
 *         user.setEmail(param.getEmail());
 *         user.setNickName(param.getNickName());
 *         // password 字段名不同 → 需要 @Mapping 指定
 *         user.setPasswordHash(param.getPassword());
 *         return user;
 *     }
 * }
 * </pre>
 *
 * ========================================================================
 * 【面试知识点 —— 两种使用方式对比】
 * ========================================================================
 *
 * 方式一：静态常量 INSTANCE（适合工具类、非 Spring 管理的场景）
 * <pre>
 *   UserInfoVO vo = AuthConvertor.INSTANCE.toUserInfoVO(user);
 * </pre>
 *
 * 方式二：Spring 注入（推荐，适合 Service / Controller 层）
 * <pre>
 *   &#64;RequiredArgsConstructor
 *   public class AuthService {
 *       private final AuthConvertor authConvertor;
 *
 *       public UserInfoVO getCurrentUser() {
 *           User user = userMapper.selectById(StpUtil.getLoginIdAsLong());
 *           return authConvertor.toUserInfoVO(user);  // 通过注入使用
 *       }
 *   }
 * </pre>
 *
 * 为什么推荐方式二？
 * - 可以通过 @MockBean 在单元测试中替换实现
 * - 遵循依赖注入原则，代码更容易测试和维护
 * - INSTANCE 方式虽然方便，但会导致 Service 和 MapStruct 实现类强耦合
 *
 * ========================================================================
 */
@Mapper(componentModel = "spring")
public interface AuthConvertor {

    /**
     * 【面试知识点 —— INSTANCE 静态常量的原理】
     *
     * Mappers.getMapper() 的内部工作流程：
     * 1. 接收接口 Class 对象（AuthConvertor.class）
     * 2. 按照约定拼接实现类全限定名：接口名 + "Impl"
     *    → com.base.auth.convertor.AuthConvertorImpl
     * 3. 通过 Class.forName() 加载该类
     * 4. 通过反射创建实例并缓存
     *
     * 常见踩坑场景：
     * - 忘记在父 pom.xml 配置 mapstruct-processor 注解处理器
     *   → 编译时不会生成 Impl 类 → Mappers.getMapper() 抛异常
     * - Lombok 和 MapStruct 的注解处理器顺序问题
     *   → 必须在 annotationProcessorPaths 中把 Lombok 放在 MapStruct 前面
     *   → 否则 MapStruct 生成的代码中 getter/setter 方法还没被 Lombok 生成
     *   → 本项目已在父 pom.xml 中正确配置了顺序
     */
    AuthConvertor INSTANCE = Mappers.getMapper(AuthConvertor.class);

    // =====================================================================
    //  User Entity → UserInfoVO（获取用户信息接口）
    // =====================================================================

    /**
     * 将 User 实体转换为前端用户信息 VO。
     *
     * 【面试知识点 —— 安全最佳实践：隐藏密码哈希】
     *
     * 旧写法（Controller 中手动置空，不优雅）：
     * <pre>
     *   User user = authService.getCurrentUser();
     *   user.setPasswordHash(null);  // 手动修改了 Entity 对象！
     *   return Result.success(user);
     * </pre>
     *
     * 问题：
     * 1. 直接修改了从数据库查出来的 Entity 对象（脏对象）
     * 2. 如果这个 user 对象后续还要用，passwordHash 已经被清空了
     * 3. 返回类型是 User，前端依然能看到 passwordHash 字段名（值为 null）
     *
     * 新写法（MapStruct 转换器，优雅）：
     * <pre>
     *   User user = authService.getCurrentUser();
     *   UserInfoVO vo = authConvertor.toUserInfoVO(user);  // 转换为 VO
     *   return Result.success(vo);
     * </pre>
     *
     * 优势：
     * 1. 不修改原始 Entity 对象
     * 2. UserInfoVO 中根本没有 passwordHash 字段 → JSON 序列化时不会出现
     * 3. Entity 和 VO 职责分离，符合单一职责原则
     *
     * 【字段映射关系】
     * - id            → id            （同名，自动映射）
     * - email         → email         （同名，自动映射）
     * - nickName      → nickName      （同名，自动映射）
     * - useSpace      → useSpace      （同名，自动映射）
     * - totalSpace    → totalSpace    （同名，自动映射）
     * - lastLoginTime → lastLoginTime （同名，自动映射）
     * - gmtCreate     → gmtCreate     （同名，自动映射）
     *
     * 【未映射的字段（自动忽略）】
     * - passwordHash：UserInfoVO 中没有此字段，MapStruct 不会映射（安全！）
     * - gmtModified：前端不需要展示最后修改时间
     * - deleted：逻辑删除标记，内部使用
     *
     * @param user 数据库实体（从 user 表查询）
     * @return 前端用户信息 VO（不含敏感字段）
     */
    UserInfoVO toUserInfoVO(User user);

    // =====================================================================
    //  RegisterParam → User Entity（注册接口入参转换）
    // =====================================================================

    /**
     * 将注册参数转换为 User 实体。
     *
     * 【面试知识点 —— @Mapping(source, target) 字段名不同的映射】
     *
     * RegisterParam 中字段名是 password（明文密码）
     * User 实体中字段名是 passwordHash（加密后的哈希）
     *
     * 字段名不同，需要用 @Mapping 显式指定映射关系：
     *   @Mapping(source = "password", target = "passwordHash")
     *
     * 注意：这里只是把值复制过去，密码加密（BCrypt）需要在 Service 层单独处理！
     *
     * 【Service 层使用示例】
     * <pre>
     *   public Long register(RegisterParam param) {
     *       // 1. MapStruct 转换基础字段
     *       User user = authConvertor.toUser(param);
     *
     *       // 2. 手动加密密码（MapStruct 不做业务逻辑！）
     *       user.setPasswordHash(
     *           passwordEncoder.encode(param.getPassword())
     *       );
     *
     *       // 3. 设置默认值
     *       user.setUseSpace(0L);
     *       user.setTotalSpace(1024 * 1024 * 1024 * 10L); // 默认 10GB
     *
     *       // 4. 保存
     *       userMapper.insert(user);
     *       return user.getId();
     *   }
     * </pre>
     *
     * 【面试知识点 —— MapStruct 只做映射，不做业务逻辑】
     *
     * 一个常见的错误是在转换器中写加密、校验等业务逻辑。
     * MapStruct 的职责是纯粹的字段拷贝，业务逻辑应该放在 Service 层。
     *
     * 如果确实需要在映射时做转换，可以用 @Mapping 的 expression 或 qualifiedByName：
     * <pre>
     *   // 不推荐：在转换器中做业务逻辑
     *   &#64;Mapping(target = "passwordHash",
     *          expression = "java(passwordEncoder.encode(param.getPassword()))")
     *
     *   // 推荐：Service 层单独处理
     *   user.setPasswordHash(passwordEncoder.encode(param.getPassword()));
     * </pre>
     *
     * @param param 注册请求参数
     * @return User 实体（passwordHash 字段为明文，需要 Service 层加密）
     */
    @Mapping(source = "password", target = "passwordHash")
    User toUser(RegisterParam param);
}
