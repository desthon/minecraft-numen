# Litematica 联动:同伴照着玩家的投影施工

> 玩法侧一句话:Litematica 里把图纸放好,直接跟同伴说"照我投影盖",它盖在**同一格**、
> 同一个朝向、同一个镜像。

## 它解决什么

施工侧(`blueprint` 工具)一直认的是"图纸名 + 最小角锚点 + 顺时针旋转 + 镜像"。而玩家
脑子里那张图是"我在 Litematica 里摆好的那栋"——图纸名他知道,锚点、旋转、镜像他不知道,
他只知道屏幕上那个半透明的影子长什么样。

于是此前这条链上横着一个**只有玩家能做、且做错了没有任何反馈**的翻译步骤:自己读投影的
原点、自己数旋转、自己判断镜像,再把四个数字和两个枚举念给同伴。翻译偏一格,图纸照样
一格一格盖起来,每一格的报告都是绿的,只是盖在隔壁。

这个联动把那一步拿掉:投影的锚点、旋转、镜像由代码读出来,**和玩家屏幕上看到的完全一致**。

## 数据只活在客户端

Litematica 是客户端模组,投影状态(图纸文件路径、原点、旋转、镜像、启用开关)只存在于
玩家的客户端进程里。因此:

- 工具覆写 `NumenTool.invoke`(而不是 `onServerCall`),**整件事在客户端当场办完**;
- 结论(图纸名 + 锚点 + 旋转 + 镜像)再经 `ServerToolTransport.ship` 交给服务端既有的
  `blueprint` / `blueprint_read`——**动身体的那部分一行都没有重写**,也永远不会和
  直接调 `blueprint` 的结果分家;
- 转发用**同一个 tool_call id**:引擎按 id 认领结果,换个 id 的话这次调用永远等不到回音,
  而身体那边照样在施工。

这条通路是现成的:Numen 的大脑本来就跑在客户端(玩家的 API Key 付费)。

## 为什么不引用 Litematica 的类

同一个模组在两个加载器上是两个移植体(Fabric 的 Litematica、Forge 的 Forgematica),版本线
也各走各的(0.15 ~ 0.19)。编译期捆一个具体 jar,等于把另一个移植体与其余版本一起判死,
而这两样都不归我们管。所以 `LitematicaBridge` 全部走反射,只认**公开方法名**——那正是
Litematica 自己的界面与配置在用的口。做法与 YSM 联动同款(那边只用命令和 NBT 键名)。

**反射只用在 Litematica 自己的类上,原版对象一律用编译期类型接住**(`(BlockPos) invoke(...)`)
——这一点是硬约束,不是风格:原版成员名在 Fabric 运行期是 intermediary、在 Forge 1.20.1 是
SRG,`getMethod("getX")` 在两边都找不到;而编译期写的 `pos.getX()` 会被各自的重映射器换成
正确的名字。

## 几何:锚点是怎么算出来的

Litematica 摆放方块用的是这个式子(见其 `LitematicaSchematic.placeBlocksToWorld`):

```
world(格) = origin + T(区域最小角 + 格内偏移)
```

`T` = **先镜像、后旋转**(`PositionUtils.getTransformedBlockPos`,镜像:left_right 翻 Z、
front_back 翻 X;旋转与原版 `Vec3i.rotate` 同式)。而 Numen 施工侧的锚点是"变换后整幢东西
的最小角",于是:

```
anchor = origin + T(图纸原点) + min over 盒内所有格 of T(格)
        = origin + T(图纸原点) + (变换后包围盒的最小角)
```

`图纸原点` = 各区域包围盒的最小角,再对所有区域取分量最小——**与 Numen 读同一个 .litematic
文件时的归一化同源**(见 `BlueprintFormats.regionMin` / `regionAbsSize`)。两边的线性部分相同、
最小角对齐,两个映射就是同一个映射。

`T` 没有自己实现一份:直接反射调 Litematica 的 `PositionUtils.getTransformedBlockPos`。
抄错这一份的表现是"房子盖对了位置、楼梯朝着墙",而且坐标全对——**不复制约定,只调用约定**。

### 为什么施工侧补了镜像

投影可以镜像,而施工侧原先只认旋转。少这一半的后果不是报错,是"塔从左边跑到右边,格子数
一模一样"。所以这次一并补上:

- `BlueprintOrientation`(core/common):先镜像后旋转 + 平移对齐,**纯数学、可单测**;
- `BlueprintStore.load(..., Mirror)`:方块状态与坐标走同一个顺序(`state.mirror(m).rotate(r)`);
- 实体(展示框/盔甲架)用**连续包围盒**的平移量:格子占 `0..边长-1`,实体占 `[0, 边长]`,
  差这一格,展示框会整体偏一格;
- `blueprint` / `blueprint_read` 两个工具各多一个 `mirror` 参数(枚举名认不出来**当场报错**,
  静默降级成"不镜像"会盖出一栋镜像过的房子而每格都报成功)。

## 服务端那边要有什么

施工读的是**服务端**的图纸目录:`BlueprintStore.dir()` = `<运行目录>/schematics`。
1.20.1 的 `MinecraftServer.getServerDirectory()` 返回 `new File(".")`(已用映射后的 jar 核对),
所以:

- **单机**:运行目录就是 `.minecraft`,`schematics/` 正是 Litematica 的默认图纸目录
  (`DataManager.getSchematicsBaseDirectory()`)。**图纸不用搬**,玩家在 Litematica 里能加载的,
  同伴就能建;
- **专用服务器**:图纸在玩家自己的机器上,必须由玩家(或管理)拷到服务端的 `schematics/`。
  文件找不到时,工具会把**客户端上的绝对路径**附在错误里——那就是他该复制过去的那个文件。

## 两个加载器的接线

| | Forge | Fabric |
|---|---|---|
| 闸门 | `core/forge` 的 `Builtin.gateOnClass` | `core/fabric` 的 `Builtin.gateOnClass`(本次新增) |
| 挂载点 | `NumenCoreForge` 构造函数 | `NumenCoreFabricClient.onInitializeClient` |
| 平铺 | `jar` / `jarJar` 的 `from(plugins:litematica.sourceSets.main.output)` | `jar` 的 `from(...)` |

判据用**类在不在**而不是 mod id:两个移植体的 id 不一定一样,包名是同一个。
在客户端入口挂载,因为 Litematica 是客户端模组——专用服务器上既没有它,也没有要读的投影。

**同一份编译产物平铺进两个 jar** 是可行的,因为本联动只引用原版类:Forge 的 `reobfJar` 与
loom 的 `remapJar` 都是"把 jar 里的类一起重映射",对平铺进来的类与自家源码同等对待。

## 做不了的(以及为什么)

| 情况 | 表现 | 处理 |
|---|---|---|
| 区域尺寸为负(文件"反着长") | 归一化会与 Litematica 的摆放式子错开 | `buildable: false`,说明原因 |
| 子区域被单独转过/镜像过 | 整张图不是"一个旋转 + 一个镜像"能表达 | `buildable: false`,说明原因 |
| 只在内存里的临时投影(无文件) | 服务端无从读起 | 列表里不出现 |
| 投影在 Litematica 里是关着的 | 玩家屏幕上什么都没有,同伴却在盖楼 | 照常施工,回执里点名 |

## 还没接的口(留给下一个人)

这次只用"放置"(`SchematicPlacementManager`),以下都在 Litematica 的公开 API 里,做起来
是同样的形状:

- **选区**(`DataManager.getSelectionManager().getCurrentSelection()`):玩家框一块区域,
  "把这里存成图纸"——正好是反向导出(写 .litematic)的入口;
- **材料清单**(`SchematicPlacement.getMaterialList()`):Litematica 自己算好的缺料表,
  与 `blueprint_read` 的报价可以互相印证;
- **校验器**(`getSchematicVerifier()`,含 `SchematicVerifier.getBlockResults()`):
  Litematica 逐格比对世界的结果,能直接用来说"还差哪几面墙"。

## 怎么验证

### 已经验过的(开发机 1.20.1 分支,禁网环境用镜像重跑)

| 项 | 结论 |
|---|---|
| `:core:common:test` | 6 项全过(见下) |
| `:core:forge:compileJava` / `:core:fabric:compileJava` | 通过 |
| `:core:forge:build` / `:core:fabric:build`(含 reobf / remap) | 通过 |
| 产物内含联动类与技能 | 两个 jar 里都有 `com/dwinovo/numen/plugins/litematica/*.class` 与 `plugins/litematica/skills/litematica_build/SKILL.md` |
| Forge 侧重映射 | 平铺进去的类被 `reobfJarJar` 换成 SRG 名(`BlockPos.m_123341_`),与 core 自己的类同等对待 |
| Fabric 侧重映射 | 被 loom 的 `remapJar` 换成 intermediary(`class_2338.method_10263`、`class_2415.field_11300`) |
| 闸门 | 两个 jar 的 `plugins.Builtin` 里都有类名判据与 `NumenLitematica.install` 调用 |

`BlueprintOrientationTest` 的 6 项:

1. **旋转与原手写公式逐字一致** —— 改造只换实现不换行为;
2. **镜像在旋转之前** —— 并断言"先旋转后镜像"会落到别处(顺序无所谓是错觉);
3. **变换后恰好铺满包围盒** —— 12 种镜像×旋转组合×各尺寸,不重叠、不留空;
4. **实体平移量差一格** —— 格子占 `0..边长-1`,实体占 `[0, 边长]`;
5. **未知镜像名当场报错** —— 不允许静默降级成"不镜像";
6. **与原版方向变换一致** —— 用原版自己的 `Mirror.mirror(Direction)` 与
   `Rotation.rotate(Direction)`(即 `BlockState.mirror/rotate` 的内核)当金标准逐方向比对。
   **这一条是"楼梯朝着墙"的唯一防线**:坐标与方块状态是两份独立的变换,配对错了格子全对、
   朝向全反。

### 进游戏要验的(开发机上跑不了)

装 Litematica + 本项目,加载一张 .litematic 放进世界:

1. `litematica action=list` —— 锚点是否等于屏幕上那个影子的最小角;
2. `action=build` 盖章,与投影逐格对齐(**镜像与 90° 旋转各测一遍**:这两种最容易漏);
3. `action=check` 在一栋半成品上跑 —— 已立格数应与肉眼一致;
4. 图纸只放在客户端、服务端 `schematics/` 里没有时,报错应带上客户端的绝对路径。

### 回归

`blueprint` 工具**不带** `mirror` 参数时的行为必须与改动前完全一致(等价于 `mirror=none`)——
第 1、3 两项测试守的就是这条。
