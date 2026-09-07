#!/bin/sh
# ==============================================================================
# WanXiang 共享镜像库：GitHub 加速节点全量列表 + 并行测速选最快函数。
# 被 setup_termux_ndk.sh / setup_android_core.sh / setup_flutter.sh 等 source。
# 节点来自 github.akams.cn 的实时节点列表（contribute + search），
# 用法均为 <node>/<完整 github-url> 前缀形式；官方直连由调用方自行兜底。
# ==============================================================================

WANXIANG_MIRROR_NODES="
https://gh.dpik.top/
https://github.tbap.top/
https://ghfile.geekertao.top/
https://ghproxy.net/
https://gh-proxy.com/
https://cdn.gh-proxy.com/
https://github.dpik.top/
https://j.1lin.dpdns.org/
https://github.starrlzy.cn/
https://github-proxy.memory-echoes.cn/
https://git.yylx.win/
https://ghm.078465.xyz/
https://gh.927223.xyz/
https://ghf.无名氏.top/
https://gh.felicity.ac.cn/
https://gh.bugdey.us.kg/
https://cdn.akaere.online/
https://jiashu.1win.eu.org/
https://tvv.tw/
https://j.1win.ggff.net/
https://gitproxy.127731.xyz/
https://gh.inkchills.cn/
https://gh.catmak.name/
https://gh.b52m.cn/
https://down.mxw.xx.kg/
https://down.mxw.qzz.io/
https://github.mxw.qzz.io/
https://gh.acmsz.top/
https://gh.jjj.gv.uy/
https://slink.ltd/
https://github.tmby.shop/
https://ghpr.cc/
https://gh.tryxd.cn/
https://gitproxy.click/
https://github.chenc.dev/
https://gh.ddlc.top/
https://gitproxy.mrhjx.cn/
https://gh.sixyin.com/
https://gh.monlor.com/
https://ghpxy.hwinzniej.top/
https://git.669966.xyz/
https://ghfast.top/
https://gh.jasonzeng.dev/
https://github.geekery.cn/
https://gp.zkitefly.eu.org/
https://fastgit.cc/
https://ghproxy.1888866.xyz/
https://ghp.arslantu.xyz/
https://github.ednovas.xyz/
https://ghproxy.imciel.com/
https://ghproxy.cxkpro.top/
https://github.xxlab.tech/
https://gh.idayer.com/
https://free.cn.eu.org/
https://gh.chjina.com/
https://ghp.keleyaa.com/
https://proxy.yaoyaoling.net/
https://ghproxy.monkeyray.net/
https://gh.noki.icu/
https://g.blfrp.cn/
https://githubdog.com/
https://gh.meali.top/
https://777.z321.cc.cd/
https://gg.z321.cc.cd/
https://g.z321.cc.cd/
https://js.jiangss.shop/
https://gap.andyjin.website/
https://gh.my-website.ccwu.cc/
https://github.ikgy.top/
https://gh.07150721.xyz/
https://cfgh.ikgy.top/
https://xsadwsd.kdns.fr/
https://gh.ruan.dpdns.org/
https://ghproxy.felicity.land/
https://github.nswrz.cn/
https://gh.zhai.edu.pl/
https://gh.qfmc0721.cc.cd/
https://github-cf.947563.xyz/
"

# 并行测速：对全量候选节点各拉 256KB 探测字节（HTTP Range），并发 16 路，
# 选耗时最短者。全部失败输出空，调用方回退官方直连。
pick_fastest_mirror() {
    upstream="$1"
    results_dir="/tmp/wanxiang-mirror-probe-$$"
    rm -rf "$results_dir"
    mkdir -p "$results_dir"
    n=0
    for base in $WANXIANG_MIRROR_NODES; do
        [ -n "$base" ] || continue
        n=$((n + 1))
        (
            probe="${base}${upstream}"
            t=$(curl -fsSL -m 10 -r 0-262143 -o /dev/null -w '%{time_total}' "$probe" 2>/dev/null)
            if [ $? -eq 0 ] && [ -n "$t" ]; then
                printf '%s %s\n' "$t" "$base" > "$results_dir/$n"
            fi
        ) &
        if [ $((n % 16)) -eq 0 ]; then
            wait
        fi
    done
    wait
    fastest=""
    if ls "$results_dir"/* >/dev/null 2>&1; then
        fastest=$(cat "$results_dir"/* 2>/dev/null | sort -n | head -n 1 | awk '{print $2}')
    fi
    rm -rf "$results_dir"
    printf '%s' "$fastest"
}
