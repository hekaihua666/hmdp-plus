/** 共用底栏：原来无行为的地图/消息占位替换成已有业务的附近/关注入口。 */
Vue.component("footBar", {
  template: `
    <div class="foot">
    <div class="foot-box" :class="{active: activeBtn === 1}" @click="toPage(1)">
      <div class="foot-view"><i class="el-icon-s-home"></i></div>
      <div class="foot-text">首页</div>
    </div>
    <div class="foot-box" :class="{active: activeBtn === 2}" @click="toPage(2)">
      <div class="foot-view"><i class="el-icon-location-outline"></i></div>
      <div class="foot-text">附近</div>
    </div>
    <div class="foot-box" @click="toPage(0)">
      <img class="add-btn" src="/imgs/add.png" alt="">
    </div>
    <div class="foot-box" :class="{active: activeBtn === 3}" @click="toPage(3)">
      <div class="foot-view"><i class="el-icon-star-off"></i></div>
      <div class="foot-text">关注</div>
    </div>
    <div class="foot-box" :class="{active: activeBtn === 4}" @click="toPage(4)">
      <div class="foot-view"><i class="el-icon-user"></i></div>
      <div class="foot-text">我的</div>
    </div>
  </div>
  `,
  data() {
    return {
    }
  },
  props: ['activeBtn'],
  methods: {
    toPage(i) {
      // 跳转只选择页面；发布、个人页和关注列表的权限仍由页面与后端共同检查。
      if (i === 0) {
        location.href = "/blog-edit.html"
      } else if (i === 4) {
        location.href = "/info.html"
      } else if (i === 2) {
        // 显式选择“5公里+距离排序”，避免把普通分类查询也默认限制在5公里内。
        location.href = "/shop-list.html?sort=distance&radius=5000"
      } else if (i === 3) {
        // tab只是个人页的初始标签，不携带userId，不允许据此选择别人的私有关注列表。
        location.href = "/info.html?tab=following"
      } else if (i === 1){
        location.href = "/"
      }
    }
  }
})
