# 产物等价判定（PVM-05）

`compare_objects.py` 把「两个 `.o` 是否等价」从 SHA-256 比对升级为**四级判据**，
替代 AX-06 的 SHA 判据。判定按顺序短路，输出三种结论：

| 结论 | 含义 | 退出码 |
| --- | --- | --- |
| `EQUIVALENT` | 四级全部通过 | 0 |
| `DIFFERENT` | 存在真实差异，detail 行给出第一个差异点 | 1 |
| `INCONCLUSIVE` | 工具或夹具缺失/不可解析，**不猜** | 2 |

## 四级判据

1. **链接**：两个 `.o` 分别与同一组 fixture（`fixtures/<组>/main.c|main.cpp`）链接成
   可执行文件。一侧失败一侧成功 → `DIFFERENT`；两侧同因失败 → `INCONCLUSIVE`。
2. **行为**：两个可执行文件运行后**退出码与 stdout 必须一致**；单侧挂起 →
   `DIFFERENT`，双侧挂起 → `INCONCLUSIVE`。
3. **结构**：`nm -C --defined-only` 的（类型, 符号名）集合一致；`readelf -r` 的
   （重定位类型, 目标）多重集一致。
4. **差异白名单**：除 `.comment`、`.debug_*` 外所有 section 的字节必须一致；
   唯一豁免是**路径类字符串**（构建目录、`__FILE__` 残留）——差异区域必须全部
   由含路径分隔符的路径形 token 组成，任何非路径字节差异都判 `DIFFERENT`。

## 用法

```bash
# 生成两个候选 .o（同一源码、不同构建目录），然后判定：
python tools/compiler-equivalence/compare_objects.py \
    --a build-a/candidate.o --b build-b/candidate.o \
    --fixtures tools/compiler-equivalence/fixtures/c
```

- `--fixtures` 指向含 `main.c`（C）或 `main.cpp`（C++）的目录；
  `fixtures/c`、`fixtures/cpp`、`fixtures/error` 各一组，候选对象必须定义
  `fixture_entry()`（示例见各目录 `candidate_example.*`）。
- `--cc/--cxx/--nm/--readelf` 可覆盖工具；默认按 `cc/gcc/clang`、
  `c++/g++/clang++`、`nm`、`readelf` 顺序探测，缺失即 `INCONCLUSIVE`。
- 只用 host 的 binutils/llvm 工具，永不接触设备。PE/COFF 对象（Windows 本地
  gcc 产物）无法被 readelf 解析，结构级判据在其上必然 `INCONCLUSIVE`——
  这是显式边界，不是缺陷。

## 确定性编译开关（减少噪声，建议双方统一启用）

```bash
-frandom-seed=<stable-string>     # 钉死编译器内部随机种子
-ffile-prefix-map=<old>=<new>     # 把构建路径映射为规范前缀
-fdebug-compilation-dir=<dir>     # 钉死调试信息中的编译目录（clang）
-fno-ident                        # 去掉 .comment 中的版本 ident（如适用）
```

开关只能**减少**噪声，判据本身不依赖它们。

## 误判边界

- **跨编译器必判 `DIFFERENT`**：本工具针对「同一编译器、不同编译实例/路径」
  （guest 预处理 + payload 同源 clang codegen）。tcc 样机产物对 clang 产物
  判 `DIFFERENT` 是正确行为，不是误报。
- **能抓到**：优化级别差异、常量改动（数据/代码段字节不同）、符号增删、
  ABI 级重定位差异、单侧链接失败、行为差异。
- **抓不到的**：发生在被白名单豁免区域内的差异（`.comment`/`.debug_*` 的
  内容变化、纯路径字符串变化）。这类差异不进入链接产物语义，属于设计豁免。
- **LTO/胖对象**：LTO 字节码在链接期才定形，结构判据会看到更大的假差异面；
  v1 不支持 LTO 对象比对。
- **哈希的角色**：SHA-256 仍是「同源、同参数」构建的廉价辅助信号（AX-06 的
  原场景）；一旦编译实例或路径可能不同，以本工具判据为准，SHA 不再作为判据。
