# Specification: Camera Device Connection (相机设备连接)

## ADDED Requirements

### Requirement: 设备路径直接连接
系统 SHALL 支持通过设备文件路径（如 `/dev/video0`）直接连接 V4L2 相机设备。

#### Scenario: 通过有效设备路径连接成功
- **GIVEN** 设备路径 `/dev/video23` 存在且具有读写权限
- **AND** 该设备是有效的 V4L2 视频捕获设备
- **WHEN** 调用 `connectByPath("/dev/video23")`
- **THEN** 系统返回 `ACTION_SUCCESS`
- **AND** 设备文件描述符被成功打开
- **AND** 相机状态变更为 `STATUS_OPEN`
- **AND** 日志记录连接成功信息

#### Scenario: 设备路径不存在
- **GIVEN** 设备路径 `/dev/video99` 不存在于文件系统
- **WHEN** 调用 `connectByPath("/dev/video99")`
- **THEN** 系统返回 `ACTION_ERROR_OPEN_FAIL`
- **AND** 错误日志记录 "No such file or directory"
- **AND** 相机状态保持为 `STATUS_CREATE`

#### Scenario: 设备路径权限不足
- **GIVEN** 设备路径 `/dev/video0` 存在但权限为 `crw------- root video`
- **AND** 应用进程没有 root 权限
- **WHEN** 调用 `connectByPath("/dev/video0")`
- **THEN** 系统返回 `ACTION_ERROR_OPEN_FAIL`
- **AND** 错误日志记录 "Permission denied"
- **AND** 相机状态保持为 `STATUS_CREATE`

#### Scenario: 设备路径格式无效
- **GIVEN** 用户传入路径 `/etc/passwd`（非视频设备）
- **WHEN** 调用 `connectByPath("/etc/passwd")`
- **THEN** 系统返回 `ACTION_ERROR_INVALID_PATH`
- **AND** 错误日志记录 "Invalid device path format"
- **AND** 不尝试打开该文件

#### Scenario: 设备不支持视频捕获
- **GIVEN** 设备路径 `/dev/video10` 存在但不具备 `V4L2_CAP_VIDEO_CAPTURE` 能力
- **WHEN** 调用 `connectByPath("/dev/video10")`
- **THEN** 系统返回 `ACTION_ERROR_START`
- **AND** 错误日志记录 "Device does not support video capture"
- **AND** 设备文件描述符被关闭

### Requirement: 设备路径验证
系统 SHALL 验证设备路径的合法性，防止恶意或错误的路径输入。

#### Scenario: 路径格式验证通过
- **GIVEN** 设备路径为 `/dev/video0`, `/dev/video23`, 或 `/dev/video99`
- **WHEN** 执行路径格式验证
- **THEN** 验证通过，返回 true

#### Scenario: 路径格式验证失败 - 非 /dev/video 前缀
- **GIVEN** 设备路径为 `/dev/null`, `/tmp/video0`, 或 `video0`
- **WHEN** 执行路径格式验证
- **THEN** 验证失败，返回 false

#### Scenario: 路径格式验证失败 - 符号链接到非法位置
- **GIVEN** 设备路径为 `/dev/camera` 且其符号链接指向 `/etc/passwd`
- **WHEN** 执行路径验证（包括 realpath 解析）
- **THEN** 验证失败，返回 false
- **AND** 错误日志记录 "Resolved path is not a video device"

### Requirement: 设备打开逻辑重构
系统 SHALL 提取公共的设备打开逻辑，供 PID/VID 连接和路径连接共享使用。

#### Scenario: PID/VID 连接使用重构后的打开逻辑
- **GIVEN** 设备 USB PID=1234, VID=5678 对应 `/dev/video5`
- **WHEN** 调用 `connect(1234, 5678)`
- **THEN** 系统查找设备路径为 `/dev/video5`
- **AND** 调用内部 `openDevice("/dev/video5")` 方法
- **AND** 设备成功打开，状态变为 `STATUS_OPEN`

#### Scenario: 路径连接使用重构后的打开逻辑
- **GIVEN** 设备路径 `/dev/video5` 有效
- **WHEN** 调用 `connectByPath("/dev/video5")`
- **THEN** 系统验证路径格式
- **AND** 调用内部 `openDevice("/dev/video5")` 方法
- **AND** 设备成功打开，状态变为 `STATUS_OPEN`

### Requirement: Java 层 API 支持
系统 SHALL 在 Java 层提供设备路径连接的公共 API。

#### Scenario: Java 调用路径连接 API
- **GIVEN** Java 应用持有 `CameraAPI` 实例
- **AND** 设备路径 `/dev/video0` 有效
- **WHEN** 调用 `cameraAPI.connectByPath("/dev/video0")`
- **THEN** JNI 层转发调用到 Native 层
- **AND** Native 层成功打开设备
- **AND** Java 方法返回 `ACTION_SUCCESS` (0)

#### Scenario: Java 层参数验证 - 空路径
- **GIVEN** Java 应用调用 `connectByPath(null)`
- **WHEN** Java 层执行参数验证
- **THEN** 抛出 `IllegalArgumentException`
- **AND** 异常消息为 "Device path cannot be null"
- **AND** 不调用 Native 层

#### Scenario: Java 层参数验证 - 空字符串
- **GIVEN** Java 应用调用 `connectByPath("")`
- **WHEN** Java 层执行参数验证
- **THEN** 抛出 `IllegalArgumentException`
- **AND** 异常消息为 "Device path cannot be empty"
- **AND** 不调用 Native 层

### Requirement: 向后兼容性
系统 SHALL 保持与现有 PID/VID 连接方式的完全兼容，不得破坏现有功能。

#### Scenario: 现有 PID/VID 连接方式正常工作
- **GIVEN** 系统已部署设备路径连接功能
- **AND** 设备 USB PID=1234, VID=5678 存在
- **WHEN** 应用调用 `connect(1234, 5678)`
- **THEN** 设备成功连接
- **AND** 所有现有功能（预览、录制、参数设置）正常工作
- **AND** 行为与新功能部署前完全一致

#### Scenario: 不同连接方式可互换
- **GIVEN** 设备通过 `connect(1234, 5678)` 连接后关闭
- **WHEN** 再次通过 `connectByPath("/dev/video5")` 连接相同设备
- **THEN** 连接成功
- **AND** 设备功能正常
- **AND** 无状态残留或冲突

### Requirement: 错误处理和日志
系统 SHALL 为设备路径连接提供详细的错误信息和日志记录。

#### Scenario: 详细的错误日志 - 打开失败
- **GIVEN** 设备路径 `/dev/video0` 打开失败，errno=EACCES
- **WHEN** 调用 `connectByPath("/dev/video0")`
- **THEN** 系统记录错误日志包含：
  - 日志级别：ERROR
  - 标签：CameraAPI
  - 消息："open: /dev/video0 failed, Permission denied"
- **AND** 返回 `ACTION_ERROR_OPEN_FAIL`

#### Scenario: 详细的成功日志
- **GIVEN** 设备路径 `/dev/video23` 成功打开
- **WHEN** 调用 `connectByPath("/dev/video23")`
- **THEN** 系统记录调试日志包含：
  - 日志级别：DEBUG
  - 标签：CameraAPI
  - 消息："open: /dev/video23 succeed"

#### Scenario: 路径验证失败的警告日志
- **GIVEN** 用户传入无效路径 `/tmp/fake_video`
- **WHEN** 调用 `connectByPath("/tmp/fake_video")`
- **THEN** 系统记录警告日志：
  - 日志级别：WARN
  - 标签：CameraAPI
  - 消息："connectByPath: invalid device path format: /tmp/fake_video"
