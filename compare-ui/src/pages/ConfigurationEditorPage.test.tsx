import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigurationEditorPage } from './ConfigurationEditorPage'
import { renderPage } from '../test/render'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import type { ConfigurationDetail } from '../api/types'

const nameModeConfiguration = {
  basic: { bcompIndex: 8, name: '客户余额核对', module: '', memo: '', range: '', timeNode: 'D', state: '1', partitionNum: 1, dateMode: '1' },
  tableSql: { origDatabaseId: 1, destDatabaseId: 2, origSql: 'select ID, AMOUNT from old_customer', destSql: 'select AMOUNT, ID from new_customer' },
  fields: [
    { name: 'ID', type: '1', oldIndex: 37, newIndex: 0, indexFlag: '1' },
    { name: 'AMOUNT', type: '2', oldIndex: 37, newIndex: 0, indexFlag: '0' }
  ]
}

function nameModeResponses() {
  server.use(
    http.get('/api/configuration-options', () => HttpResponse.json({ states: ['1'], enums: [], ignoreValues: [], fieldMatchByName: true })),
    http.get('/api/configurations/8', () => HttpResponse.json(nameModeConfiguration))
  )
}

test('name mode hides positions and saves configuration with legacy positions', async () => {
  nameModeResponses()
  const user = userEvent.setup()
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  expect(await screen.findByText('按字段英文名匹配两侧 SQL 返回列名或 AS 别名，不区分大小写。')).toBeVisible()
  expect(await screen.findByDisplayValue('AMOUNT')).toBeVisible()
  expect(screen.queryByLabelText(/字段位置/)).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '保存配置' }))
  expect(await screen.findByText('配置已保存，但缓存刷新失败')).toBeVisible()
})

test('name mode rejects field names differing only in case', async () => {
  nameModeResponses()
  const user = userEvent.setup()
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  await screen.findByText('按字段英文名匹配两侧 SQL 返回列名或 AS 别名，不区分大小写。')
  const field = await screen.findByDisplayValue('AMOUNT')
  await user.clear(field)
  await user.type(field, 'id')
  await user.click(screen.getByRole('button', { name: '保存配置' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('字段英文名必须唯一（不区分大小写）')
})

test('shows cache refresh warning after configuration is saved', async () => {
  const user = userEvent.setup()
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  await user.click(await screen.findByRole('button', { name: '保存配置' }))
  expect(await screen.findByText('配置已保存，但缓存刷新失败')).toBeVisible()
})

test('labels configuration editor sections for direct navigation', async () => {
  renderPage(<ConfigurationEditorPage />, '/configurations/8')

  expect(await screen.findByRole('region', { name: '基本信息' })).toBeVisible()
  expect(screen.getByRole('region', { name: '两侧查询' })).toBeVisible()
  expect(screen.getByRole('region', { name: '字段规则' })).toBeVisible()
})

test('names SQL and field positions after the selected database', async () => {
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  expect(await screen.findByLabelText('核心旧库 SQL')).toHaveValue('select * from old_customer')
  expect(screen.getByLabelText('核心旧库 字段位置 1')).toHaveValue(1)
  expect(screen.getByLabelText('数据库 #2 SQL')).toHaveValue('select * from new_customer')
  expect(document.body.textContent).not.toMatch(/旧侧|新侧/)
})

function partitionLimitResponses(maxPartitionNum?: number | null) {
  nameModeResponses()
  const saved: ConfigurationDetail[] = []
  server.use(
    http.get('/api/configurations/8', () => HttpResponse.json({
      ...nameModeConfiguration,
      basic: { ...nameModeConfiguration.basic, maxPartitionNum }
    })),
    http.put('/api/configurations/8', async ({ request }) => {
      const configuration = await request.json() as ConfigurationDetail
      saved.push(configuration)
      return HttpResponse.json({ configuration, saved: true, cacheRefreshed: true })
    })
  )
  return saved
}

test.each([1, 256])('shows the configured HASH partition limit and saves %i', async value => {
  const saved = partitionLimitResponses(128)
  const user = userEvent.setup()
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  await screen.findByDisplayValue('AMOUNT')
  const limit = screen.getByRole('spinbutton', { name: '最大 HASH 分区数' })
  expect(limit).toHaveValue(128)

  await user.clear(limit)
  await user.type(limit, String(value))
  await user.click(screen.getByRole('button', { name: '保存配置' }))

  await waitFor(() => expect(saved).toHaveLength(1))
  expect(saved[0].basic.maxPartitionNum).toBe(value)
})

test.each([undefined, null])('defaults a legacy HASH partition limit of %s to 64 when saving', async value => {
  const saved = partitionLimitResponses(value)
  const user = userEvent.setup()
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  await screen.findByDisplayValue('AMOUNT')
  expect(screen.getByRole('spinbutton', { name: '最大 HASH 分区数' })).toHaveValue(64)

  await user.click(screen.getByRole('button', { name: '保存配置' }))

  await waitFor(() => expect(saved).toHaveLength(1))
  expect(saved[0].basic.maxPartitionNum).toBe(64)
})

test.each(['', '0', '257', '1.5'])('rejects invalid HASH partition limit "%s" until corrected', async value => {
  const saved = partitionLimitResponses(128)
  const user = userEvent.setup()
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  await screen.findByDisplayValue('AMOUNT')
  const limit = screen.getByRole('spinbutton', { name: '最大 HASH 分区数' })
  await user.clear(limit)
  if (value) await user.type(limit, value)
  await user.click(screen.getByRole('button', { name: '保存配置' }))

  expect((limit as HTMLInputElement).validity.valid).toBe(false)
  expect(await screen.findByRole('alert')).toHaveTextContent('最大 HASH 分区数必须为 1～256 的整数')
  expect(saved).toHaveLength(0)

  await user.clear(limit)
  await user.type(limit, '64')
  await user.click(screen.getByRole('button', { name: '保存配置' }))
  await waitFor(() => expect(saved).toHaveLength(1))
  expect(saved[0].basic.maxPartitionNum).toBe(64)
})

test('preserves the returned HASH partition limit when other fields change', async () => {
  const saved = partitionLimitResponses(128)
  const user = userEvent.setup()
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  await screen.findByDisplayValue('AMOUNT')
  const name = screen.getByLabelText('配置名称')
  await user.clear(name)
  await user.type(name, '新的核对名称')
  await user.click(screen.getByRole('button', { name: '保存配置' }))

  await waitFor(() => expect(saved).toHaveLength(1))
  expect(saved[0].basic.name).toBe('新的核对名称')
  expect(saved[0].basic.maxPartitionNum).toBe(128)
})
