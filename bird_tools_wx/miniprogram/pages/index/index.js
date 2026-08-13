const DEFAULT_UNINTERESTED = '麻雀,白头鹎,绿头鸭,鸳鸯,珠颈斑鸠,喜鹊,灰喜鹊,灰椋鸟,大嘴乌鸦,小鷿鷈,凤头鷿鷈,普通鸬鹚,鸿雁,灰头绿啄木鸟,大斑啄木鸟,普通翠鸟,家燕,戴胜,白鹭,苍鹭'
Page({
  data: { input: '', messages: [], loading: false, panelOpen: false, model: 'deepseek-v4-flash', modelLabel: 'Flash', scrollInto: '', suggestions: ['北京最近有什么值得看的鸟？', '杭州哪里适合观鸟？', '普通翠鸟有什么特征？'] },
  onLoad() { this.loadPreferences() },
  loadPreferences() { const p = wx.getStorageSync('birdPreferences') || {}; this.setData({ preferences: p, model: p.model || 'deepseek-v4-flash', modelLabel: p.model === 'deepseek-v4-pro' ? 'Pro' : 'Flash' }) },
  onInput(e) { this.setData({ input: e.detail.value }) },
  useSuggestion(e) { this.setData({ input: e.currentTarget.dataset.text }); this.send() },
  togglePanel() { this.setData({ panelOpen: !this.data.panelOpen }) },
  toggleModel() { const model = this.data.model === 'deepseek-v4-pro' ? 'deepseek-v4-flash' : 'deepseek-v4-pro'; const p = Object.assign({}, this.data.preferences, { model }); wx.setStorageSync('birdPreferences', p); this.setData({ model, modelLabel: model === 'deepseek-v4-pro' ? 'Pro' : 'Flash' }) },
  openPreferences() { this.setData({ panelOpen: false }); wx.navigateTo({ url: '/pages/preferences/index' }) },
  clearChat() { this.setData({ panelOpen: false, messages: [], input: '' }) },
  send() { const content = (this.data.input || '').trim(); if (!content || this.data.loading) return; const id = Date.now(); const messages = this.data.messages.concat([{ id, role: 'user', content }]); this.setData({ messages, input: '', loading: true, scrollInto: `msg-${id}` }); const p = this.data.preferences || {}; wx.cloud.callFunction({ name: 'quickstartFunctions', data: { type: 'birdChat', question: content, messages: this.data.messages.slice(-10), preferences: p, model: this.data.model } }).then(res => { const result = res.result || {}; const answer = result.answer || result.error || '没有返回答案'; const assistantId = Date.now() + 1; this.setData({ messages: this.data.messages.concat([{ id: assistantId, role: 'assistant', content: answer, locations: result.locations || [] }]), loading: false, scrollInto: `msg-${assistantId}` }) }).catch(err => { this.setData({ messages: this.data.messages.concat([{ id: Date.now() + 1, role: 'assistant', content: err.errMsg || '请求失败，请检查云函数和 API 配置' }]), loading: false }) }) }
})
