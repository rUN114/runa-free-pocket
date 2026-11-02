# 领取状态机速览

`ClaimCoordinator` 是全项目复杂度最集中的文件。改它之前先读这一页：三条不变量、许可的生命周期和存储失败的路径。函数名可直接搜索定位；行号会漂移，不引用行号。

## 队列骨架

- 任务排队在 `tasks`（`ArrayDeque`），入口有三处：`start()`（初始发现）、`command()`（页面动作：login/refresh/check/claim/claimAll/resumeBatch/country/open）、`plan()`（按账本条目展开 CHECK/VERIFY_EPIC/CLAIM）。
- `next()` 是唯一的推进器：`advancing` + `advanceRequested` 两个标志构成跳板（trampoline），嵌套调用只登记请求、由最外层循环消化，保证同步失败链不会重入浏览器回调（测试：`synchronousFailuresAdvanceWithoutReenteringBrowserCallbacks`）。
- `active` 是唯一活动任务，携带一次性 `nonce`；所有网页回执先过 `current(nonce)` 再被采信。任务模式见 `TaskMode`（discover/ensureAuth/auth/authorizing/syncEpic/syncFab/planBatch/claim/check/verifyEpic）。

## 许可的生命周期（自动下单的唯一通路）

~~~text
网页 actionReady(stage)
  → permitAction(): FAB 档位核验 → bindBatchAccount()
  → journal.reserve(key, stage)        # 检查点：pageCheck 前置、阶段一次性、既有预约拒绝重放
  → backend.saveJournal()              # commit() 同步落盘
     ├─ 成功 → （Fab product 阶段先重装同 nonce 脚本）→ actionPermit 回复网页 → 才允许点击
     └─ 失败 → journal.rollbackUnissued(检查点) → pause → unissuedRollbackPending = true
              → clearWork() → 不放行任何许可
存储恢复后：journalAvailable() 先把回退状态保存成功，才允许继续或开始新任务。
~~~

下单后的收尾：`ordered` 仅置等待标记；只有 Epic 凭证（`requestEpicOwnership`）或 Fab 素材库只读核实（CHECK）确认后，条目才记为成功；确认不到则记 ERROR/uncertain，预约保留。

## 三条不变量

1. **许可必须先落盘。** 没有持久化检查点，不给网页放行；`rollbackUnissued` 只撤销本阶段，永不撤销已发出的更早许可（product 已放行时 submit 保存失败，product 预约原样保留）。
2. **预约永不自动重放。** `uncertain` 条目恢复后只进入只读核实（CHECK/VERIFY_EPIC），`reserve()` 见到他人预约或已授权阶段即拒绝；"待核实订单不会重复自动下单"。
3. **同时只有一个活动任务。** 队列串行推进；账号绑定（`bindBatchAccount`）与地区（`resume` 校验 country）把运行期漂移挡在许可之前。

## 其他失败路径

- **账本损坏**：`journalError` 非空即停用全部自动领取（`journalAvailable()`），界面只读。
- **账号清理**：`resetPending` 先持久化再清会话，`onSessionCleared` 成功前阻塞一切新工作。
- **凭证读取**：`EpicEntitlements.readAll` 不读完不发负结论；所有权证明校验账号/有效期/条目一一对应。
- **通用保存失败**：`saveJournal()` 失败走 `journalSaveFailed()`（pause + clearWork + 提示），由各调用方决定是否终止批次。

改动的验收底线见 AGENTS.md 与 docs/TESTING.md：涉及以上任何一条，JVM 测试必须覆盖"保存失败不放行、恢复后先落盘、uncertain 不重下单"。
