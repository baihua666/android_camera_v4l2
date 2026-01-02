# API 文档快速指南

本项目已集成 OpenAPI 规范，用于文档化 Android Camera V4L2 SDK 的 API。

## 文件说明

- **openapi.yaml** - OpenAPI 3.0 规范文件，定义了所有 API 接口
- **API.md** - Markdown 格式的完整 API 文档，包含使用示例和故障排除
- **docs/api.html** - 生成的 HTML 格式文档（需要运行生成命令）

## 可用命令

### 查看帮助

```bash
./gradlew apiDocHelp
```

显示所有可用的 API 文档相关命令。

### 验证 OpenAPI 规范

```bash
./gradlew validateOpenAPI
```

验证 `openapi.yaml` 文件的正确性。需要安装 `swagger-cli` 或使用 Docker。

### 生成 HTML 文档

```bash
./gradlew generateAPIDoc
```

从 OpenAPI 规范生成美观的 HTML 文档到 `docs/api.html`。需要安装 `redoc-cli` 或使用 Docker。

### 查看文档

```bash
./gradlew viewAPIDoc
```

在默认浏览器中打开生成的 HTML 文档。

### 在线编辑器

```bash
./gradlew openAPIEditor
```

显示在线 OpenAPI 编辑器的链接，可以直接在浏览器中编辑和预览 API 规范。

## 工具安装

### 选项 1: 使用 npm（推荐）

```bash
# 安装 Swagger CLI（用于验证）
npm install -g @apidevtools/swagger-cli

# 安装 ReDoc CLI（用于生成文档）
npm install -g redoc-cli
```

### 选项 2: 使用 Docker

无需安装 Node.js，直接使用 Docker 镜像：

```bash
# 拉取镜像
docker pull swaggerapi/swagger-cli
docker pull redocly/redoc-cli

# 验证规范
docker run --rm -v "${PWD}:/spec" swaggerapi/swagger-cli validate /spec/openapi.yaml

# 生成文档
docker run --rm -v "${PWD}:/spec" redocly/redoc-cli bundle /spec/openapi.yaml -o /spec/docs/api.html
```

### 选项 3: 在线工具（无需安装）

访问以下在线工具直接上传 `openapi.yaml` 文件：

- [Swagger Editor](https://editor.swagger.io) - 功能全面的在线编辑器
- [Redoc Demo](https://redocly.github.io/redoc/) - 美观的文档展示
- [Stoplight Studio](https://stoplight.io/studio) - 可视化编辑器

## 快速开始

1. **查看 Markdown 文档**（最简单）

   直接打开 `API.md` 文件，其中包含完整的 API 文档和使用示例。

2. **生成并查看 HTML 文档**（推荐）

   ```bash
   # 生成文档
   ./gradlew generateAPIDoc

   # 在浏览器中查看
   ./gradlew viewAPIDoc
   ```

3. **在线查看**（无需安装工具）

   访问 https://editor.swagger.io，点击 "File" -> "Import file"，选择项目根目录的 `openapi.yaml` 文件。

## 编辑 API 规范

### 本地编辑

1. 使用任何文本编辑器打开 `openapi.yaml`
2. 修改内容
3. 运行验证命令确保格式正确：
   ```bash
   ./gradlew validateOpenAPI
   ```
4. 重新生成文档：
   ```bash
   ./gradlew generateAPIDoc
   ```

### 在线编辑

1. 访问 https://editor.swagger.io
2. 导入 `openapi.yaml` 文件
3. 在线编辑并实时预览
4. 编辑完成后下载并替换本地文件

## 文档结构

OpenAPI 规范包含以下部分：

- **info** - API 基本信息（标题、版本、描述等）
- **servers** - API 服务器信息
- **tags** - API 分组标签
  - Device Management - 设备连接和生命周期管理
  - Camera Configuration - 相机参数配置
  - Video Stream - 视频流控制和数据获取
- **paths** - API 端点定义
- **components/schemas** - 数据模型定义

## 集成到 CI/CD

在持续集成流程中验证 API 规范：

```yaml
# .github/workflows/api-docs.yml 示例
name: API Documentation

on: [push, pull_request]

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Validate OpenAPI
        run: ./gradlew validateOpenAPI
```

## 常见问题

**Q: 为什么 Android SDK 使用 OpenAPI 规范？**

A: 虽然 OpenAPI 主要用于 REST API，但它提供了标准化的文档格式，方便团队协作和 API 理解。我们使用它来描述 SDK 的方法调用模式。

**Q: 生成的文档在哪里？**

A: HTML 文档生成在 `docs/api.html`，可以直接在浏览器中打开。

**Q: 如何分享文档？**

A:
- 分享 `API.md` 文件（Markdown 格式）
- 分享 `docs/api.html` 文件（HTML 格式）
- 上传到 GitHub Pages 或其他静态托管服务
- 使用 Swagger UI 或 Redoc 托管服务

## 相关资源

- [OpenAPI Specification](https://swagger.io/specification/)
- [Swagger Editor](https://editor.swagger.io)
- [ReDoc Documentation](https://github.com/Redocly/redoc)
- [Swagger CLI](https://github.com/APIDevTools/swagger-cli)

