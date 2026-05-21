import matplotlib.pyplot as plt
import numpy as np

# 设置全局学术字体，兼容 Windows(SimHei) 和 Mac(Arial Unicode MS / PingFang SC)
plt.rcParams["font.sans-serif"] = ["SimHei", "Arial Unicode MS", "PingFang SC"]
plt.rcParams["axes.unicode_minus"] = False  # 正常显示负号

# 统一的模型组别
models = ["Baseline-A", "Baseline-B", "Baseline-C", "System"]
x = np.arange(len(models))


# ==========================================
# 图 1：表 5-1 实验对照组设置 (配置矩阵表)
# ==========================================
def plot_table_5_1():
    fig, ax = plt.subplots(figsize=(10, 4), dpi=300)
    ax.axis("off")

    cols = ["组别", "检索方式", "分块方式", "意图树", "CRAG", "重排", "MCP"]
    data = [
        ["Baseline-A", "ES BM25", "原始线上chunk", "否", "否", "否", "否"],
        ["Baseline-B", "单路向量", "固定长度重分块", "否", "否", "否", "否"],
        ["Baseline-C", "BM25+向量+RRF", "原始线上chunk", "否", "否", "否", "否"],
        ["System", "多通道+rerank", "结构感知chunk", "是", "是", "是", "是"],
    ]

    table = ax.table(cellText=data, colLabels=cols, loc="center", cellLoc="center")
    table.auto_set_font_size(False)
    table.set_fontsize(10)
    table.scale(1, 2)

    # 美化表头和 System 行
    for (row, col), cell in table.get_celld().items():
        if row == 0:
            cell.set_text_props(weight="bold", color="white")
            cell.set_facecolor("#4c72b0")
        elif row == 4:  # System 行高亮
            cell.set_facecolor("#e8f0fe")

    plt.title("图 5-1 实验对照组核心模块消融设置", pad=20, fontsize=14, weight="bold")
    plt.tight_layout()
    plt.savefig("fig_5_1_control_groups.png")
    plt.close()


# ==========================================
# 图 2：表 5-2 评测集样本构成 (环形饼图)
# ==========================================
def plot_table_5_2():
    fig, ax = plt.subplots(figsize=(7, 6), dpi=300)
    labels = ["知识库正样本", "MCP直接工具样本", "MCP数据推理样本", "无答案样本"]
    sizes = [420, 20, 20, 40]
    colors = ["#4c72b0", "#dd8452", "#55a868", "#c44e52"]
    explode = (0.05, 0, 0, 0)  # 突出知识库样本

    ax.pie(
        sizes,
        explode=explode,
        labels=labels,
        colors=colors,
        autopct="%1.1f%%",
        startangle=140,
        pctdistance=0.85,
        textprops=dict(color="black", fontsize=11),
    )

    # 变成环形图 (Donut Chart)
    centre_circle = plt.Circle((0, 0), 0.70, fc="white")
    fig.gca().add_artist(centre_circle)

    plt.title("图 5-2 评测集 500 条样本分布构成", fontsize=14, weight="bold")
    plt.tight_layout()
    plt.savefig("fig_5_2_dataset_composition.png")
    plt.close()


# ==========================================
# 图 3：表 5-3 检索层指标对比 (双 Y 轴：柱状图+折线图)
# ==========================================
def plot_table_5_3():
    hit_rate = [28.6, 38.9, 46.8, 59.7]
    recall = [30.4, 41.2, 49.5, 62.8]
    precision = [8.1, 9.6, 10.8, 13.4]
    mrr = [0.162, 0.231, 0.289, 0.391]

    fig, ax1 = plt.subplots(figsize=(9, 5), dpi=300)
    width = 0.25

    ax1.bar(x - width, hit_rate, width, label="HitRate (%)", color="#4c72b0", edgecolor="black")
    ax1.bar(x, recall, width, label="Recall (%)", color="#55a868", edgecolor="black")
    ax1.bar(x + width, precision, width, label="Precision (%)", color="#c44e52", edgecolor="black")

    ax1.set_ylabel("百分比 (%)", fontsize=12)
    ax1.set_ylim(0, 80)
    ax1.set_xticks(x)
    ax1.set_xticklabels(models, fontsize=11)

    # MRR 使用折线图并在右侧 Y 轴显示
    ax2 = ax1.twinx()
    ax2.plot(x, mrr, color="#dd8452", marker="o", linewidth=2.5, markersize=8, label="MRR")
    ax2.set_ylabel("MRR 分数", fontsize=12)
    ax2.set_ylim(0, 0.5)

    # 合并图例
    lines_1, labels_1 = ax1.get_legend_handles_labels()
    lines_2, labels_2 = ax2.get_legend_handles_labels()
    ax1.legend(lines_1 + lines_2, labels_1 + labels_2, loc="upper left")

    plt.title("图 5-3 各对照组检索层核心指标对比", fontsize=14, weight="bold")
    ax1.grid(axis="y", linestyle="--", alpha=0.7)
    plt.tight_layout()
    plt.savefig("fig_5_3_retrieval_metrics.png")
    plt.close()


# ==========================================
# 图 4：表 5-4 端到端生成指标对比 (双 Y 轴：柱状图+折线图)
# ==========================================
def plot_table_5_4():
    correctness = [2.71, 3.09, 3.32, 3.86]
    faithfulness = [3.12, 3.46, 3.67, 4.01]
    relevance = [2.96, 3.31, 3.55, 3.92]
    accuracy = [44.8, 54.6, 60.8, 73.4]

    fig, ax1 = plt.subplots(figsize=(9, 5), dpi=300)
    width = 0.25

    ax1.bar(x - width, correctness, width, label="Correctness", color="#8172b3", edgecolor="black")
    ax1.bar(x, faithfulness, width, label="Faithfulness", color="#937860", edgecolor="black")
    ax1.bar(x + width, relevance, width, label="Relevance", color="#da8bc3", edgecolor="black")

    ax1.set_ylabel("生成质量评分 (1-5分)", fontsize=12)
    ax1.set_ylim(0, 5.0)
    ax1.set_xticks(x)
    ax1.set_xticklabels(models, fontsize=11)

    # 准确率 (Accuracy) 使用折线图
    ax2 = ax1.twinx()
    ax2.plot(x, accuracy, color="#d55e00", marker="s", linewidth=2.5, markersize=8, label="Answer Accuracy (%)")
    ax2.set_ylabel("整体回答准确率 (%)", fontsize=12)
    ax2.set_ylim(0, 100)

    lines_1, labels_1 = ax1.get_legend_handles_labels()
    lines_2, labels_2 = ax2.get_legend_handles_labels()
    ax1.legend(lines_1 + lines_2, labels_1 + labels_2, loc="upper left")

    plt.title("图 5-4 各对照组端到端生成质量与准确率对比", fontsize=14, weight="bold")
    ax1.grid(axis="y", linestyle="--", alpha=0.7)
    plt.tight_layout()
    plt.savefig("fig_5_4_generation_metrics.png")
    plt.close()


# ==========================================
# 图 5：表 5-5 分题型准确率与兜底对比 (分组柱状图)
# ==========================================
def plot_table_5_5():
    static_qa = [49.8, 58.4, 64.1, 71.0]
    mcp_direct = [0.0, 0.0, 0.0, 85.0]
    mcp_reason = [0.0, 0.0, 0.0, 70.0]
    fallback = [68.5, 75.0, 81.5, 89.0]

    fig, ax = plt.subplots(figsize=(10, 5), dpi=300)
    width = 0.2

    ax.bar(x - 1.5 * width, static_qa, width, label="知识库正样本 Accuracy", color="#4c72b0", edgecolor="black")
    ax.bar(x - 0.5 * width, mcp_direct, width, label="MCP直接题 Accuracy", color="#dd8452", edgecolor="black")
    ax.bar(x + 0.5 * width, mcp_reason, width, label="MCP推理题 Accuracy", color="#55a868", edgecolor="black")
    ax.bar(x + 1.5 * width, fallback, width, label="无答案题 Fallback 兜底率", color="#c44e52", edgecolor="black")

    ax.set_ylabel("百分比 (%)", fontsize=12)
    ax.set_ylim(0, 105)
    ax.set_xticks(x)
    ax.set_xticklabels(models, fontsize=11)
    ax.legend(loc="upper left", ncol=2, fontsize=10)

    # 顶部添加数值标签
    def autolabel(rects):
        for rect in rects:
            height = rect.get_height()
            if height > 0:
                ax.annotate(
                    f"{height}%",
                    xy=(rect.get_x() + rect.get_width() / 2, height),
                    xytext=(0, 3),
                    textcoords="offset points",
                    ha="center",
                    va="bottom",
                    fontsize=9,
                )

    autolabel(ax.containers[0])
    autolabel(ax.containers[1])
    autolabel(ax.containers[2])
    autolabel(ax.containers[3])

    plt.title("图 5-5 细分题型准确率与无答案兜底率对比", fontsize=14, weight="bold")
    ax.grid(axis="y", linestyle="--", alpha=0.7)
    plt.tight_layout()
    plt.savefig("fig_5_5_task_specific_metrics.png")
    plt.close()


# 执行生成
if __name__ == "__main__":
    plot_table_5_1()
    plot_table_5_2()
    plot_table_5_3()
    plot_table_5_4()
    plot_table_5_5()
    print("5 张图表生成完毕！")
