const cloud = require("wx-server-sdk");
cloud.init({
  env: cloud.DYNAMIC_CURRENT_ENV,
});

const db = cloud.database();
const https = require('https');

const requestJson = (url, options, body) => new Promise((resolve, reject) => {
  const req = https.request(url, Object.assign({ method: 'POST', headers: { 'Content-Type': 'application/json' } }, options || {}), res => {
    let text = ''; res.on('data', chunk => { text += chunk; });
    res.on('end', () => { try { resolve({ status: res.statusCode, data: JSON.parse(text || '{}') }); } catch (e) { reject(e); } });
  });
  req.on('error', reject); req.setTimeout(60000, () => req.destroy(new Error('请求超时'))); req.write(JSON.stringify(body)); req.end();
});

const birdChat = async event => {
  const key = process.env.DEEPSEEK_API_KEY || '';
  if (!key) return { error: '请在云函数环境变量中配置 DEEPSEEK_API_KEY' };
  const p = event.preferences || {};
  const model = event.model === 'deepseek-v4-pro' || p.model === 'deepseek-v4-pro' ? 'deepseek-v4-pro' : 'deepseek-v4-flash';
  const interested = p.interested_birds || '鸮,一级保护动物';
  const uninterested = p.uninterested_birds || '麻雀,白头鹎,绿头鸭,鸳鸯,珠颈斑鸠,喜鹊,灰喜鹊,灰椋鸟,大嘴乌鸦,小鷿鷈,凤头鷿鷈,普通鸬鹚,鸿雁,灰头绿啄木鸟,大斑啄木鸟,普通翠鸟,家燕,戴胜,白鹭,苍鹭';
  const system = `你是专业观鸟助手。涉及近期鸟种、数量、地点或记录时，必须基于真实观鸟记录回答，不要编造数据。关注鸟种：${interested}。不关注鸟种：${uninterested}。回答要完整，给出结论、依据、地点和观鸟建议；地点明细中不展示不关注鸟种。`;
  const messages = [{ role: 'system', content: system }].concat((event.messages || []).slice(-10).map(m => ({ role: m.role, content: m.content })));
  const body = { model, messages, stream: false, thinking: { type: 'disabled' } };
  const maxTokens = Number(p.max_tokens || ''); if (maxTokens >= 1 && maxTokens <= 8192) body.max_tokens = maxTokens;
  const result = await requestJson('https://api.deepseek.com/chat/completions', { headers: { Authorization: `Bearer ${key}` } }, body);
  if (result.status < 200 || result.status >= 300) return { error: result.data.error?.message || `DeepSeek 请求失败（${result.status}）` };
  return { answer: result.data.choices?.[0]?.message?.content || '没有返回答案', locations: [] };
};
// 获取openid
const getOpenId = async () => {
  // 获取基础信息
  const wxContext = cloud.getWXContext();
  return {
    openid: wxContext.OPENID,
    appid: wxContext.APPID,
    unionid: wxContext.UNIONID,
  };
};

// 获取小程序二维码
const getMiniProgramCode = async () => {
  // 获取小程序二维码的buffer
  const resp = await cloud.openapi.wxacode.get({
    path: "pages/index/index",
  });
  const { buffer } = resp;
  // 将图片上传云存储空间
  const upload = await cloud.uploadFile({
    cloudPath: "code.png",
    fileContent: buffer,
  });
  return upload.fileID;
};

// 创建集合
const createCollection = async () => {
  try {
    // 创建集合
    await db.createCollection("sales");
    await db.collection("sales").add({
      // data 字段表示需新增的 JSON 数据
      data: {
        region: "华东",
        city: "上海",
        sales: 11,
      },
    });
    await db.collection("sales").add({
      // data 字段表示需新增的 JSON 数据
      data: {
        region: "华东",
        city: "南京",
        sales: 11,
      },
    });
    await db.collection("sales").add({
      // data 字段表示需新增的 JSON 数据
      data: {
        region: "华南",
        city: "广州",
        sales: 22,
      },
    });
    await db.collection("sales").add({
      // data 字段表示需新增的 JSON 数据
      data: {
        region: "华南",
        city: "深圳",
        sales: 22,
      },
    });
    return {
      success: true,
    };
  } catch (e) {
    // 这里catch到的是该collection已经存在，从业务逻辑上来说是运行成功的，所以catch返回success给前端，避免工具在前端抛出异常
    return {
      success: true,
      data: "create collection success",
    };
  }
};

// 查询数据
const selectRecord = async () => {
  // 返回数据库查询结果
  return await db.collection("sales").get();
};

// 更新数据
const updateRecord = async (event) => {
  try {
    // 遍历修改数据库信息
    for (let i = 0; i < event.data.length; i++) {
      await db
        .collection("sales")
        .where({
          _id: event.data[i]._id,
        })
        .update({
          data: {
            sales: event.data[i].sales,
          },
        });
    }
    return {
      success: true,
      data: event.data,
    };
  } catch (e) {
    return {
      success: false,
      errMsg: e,
    };
  }
};

// 新增数据
const insertRecord = async (event) => {
  try {
    const insertRecord = event.data;
    // 插入数据
    await db.collection("sales").add({
      data: {
        region: insertRecord.region,
        city: insertRecord.city,
        sales: Number(insertRecord.sales),
      },
    });
    return {
      success: true,
      data: event.data,
    };
  } catch (e) {
    return {
      success: false,
      errMsg: e,
    };
  }
};

// 删除数据
const deleteRecord = async (event) => {
  try {
    await db
      .collection("sales")
      .where({
        _id: event.data._id,
      })
      .remove();
    return {
      success: true,
    };
  } catch (e) {
    return {
      success: false,
      errMsg: e,
    };
  }
};

// const getOpenId = require('./getOpenId/index');
// const getMiniProgramCode = require('./getMiniProgramCode/index');
// const createCollection = require('./createCollection/index');
// const selectRecord = require('./selectRecord/index');
// const updateRecord = require('./updateRecord/index');
// const fetchGoodsList = require('./fetchGoodsList/index');
// const genMpQrcode = require('./genMpQrcode/index');
// 云函数入口函数
exports.main = async (event, context) => {
  switch (event.type) {
    case "getOpenId":
      return await getOpenId();
    case "getMiniProgramCode":
      return await getMiniProgramCode();
    case "createCollection":
      return await createCollection();
    case "selectRecord":
      return await selectRecord();
    case "updateRecord":
      return await updateRecord(event);
    case "insertRecord":
      return await insertRecord(event);
    case "deleteRecord":
      return await deleteRecord(event);
    case "birdChat":
      return await birdChat(event);
  }
};
