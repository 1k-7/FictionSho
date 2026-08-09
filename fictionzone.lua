-- {"id":-1,"ver":"1.0.0","libVer":"1.0.0","author":"","repo":"","dep":[]}

local id = -1
local name = "FictionZone"
local baseURL = "https://fictionzone.net"
local imageURL = "https://fictionzone.net/favicon.ico"
local hasCloudFlare = true
local hasSearch = true
local isSearchIncrementing = true
local chapterType = ChapterType.HTML
local startIndex = 1

---------------------------------------------------------------------
--  JSON helper utilities (targeted extraction, no full parser)
---------------------------------------------------------------------

local function extract_json_str(json, key)
	local p = '"' .. key .. '"%s*:%s*"([^"]*)"'
	local v = json:match(p)
	return v
end

local function extract_json_num(json, key)
	local p = '"' .. key .. '"%s*:%s*(%-?%d+%.?%d*)'
	local v = json:match(p)
	return v and tonumber(v)
end

local function extract_json_bool(json, key)
	local p = '"' .. key .. '"%s*:%s*(%a+)'
	local v = json:match(p)
	if v == "true" then return true end
	if v == "false" then return false end
	return nil
end

--- Extract the "data" sub-object from a gateway response
local function extract_data(json)
	local d = json:match('"data"%s*:%s*({.+})%s*,%s*"code"')
	if not d then
		d = json:match('"data"%s*:%s*({.+})%s*}$')
	end
	return d
end

-- Unescape a JSON string
local function json_unescape(s)
	if not s then return nil end
	s = s:gsub('\\"', '"')
	s = s:gsub('\\n', '\n')
	s = s:gsub('\\t', '\t')
	s = s:gsub('\\r', '\r')
	s = s:gsub('\\\\', '\\')
	s = s:gsub('\\/', '/')
	return s
end

--- Extract chapter list entries from JSON data
local function parse_chapter_list_entries(json_data)
	local chapters = {}
	local list_str = json_data:match('"chapters"%s*:%s*(%[.-%])')
	if not list_str then
		list_str = json_data:match('"list"%s*:%s*(%[.-%])')
	end
	if not list_str then
		list_str = json_data:match('"items"%s*:%s*(%[.-%])')
	end
	if not list_str then
		return chapters
	end
	-- iterate each entry: {"id":"...","title":"...","idx":N}
	for ident, ttl, idx in list_str:gmatch('"id"%s*:%s*"([^"]+)"[^}]-"title"%s*:%s*"([^"]*)"[^}]-"idx"%s*:%s*(%d+)') do
		chapters[#chapters + 1] = {
			["id"] = ident,
			title = string.gsub(ttl, "\\u0026", "&"),
			idx = tonumber(idx)
		}
	end
	return chapters
end

---------------------------------------------------------------------
--  Gateway API helpers
---------------------------------------------------------------------

local GATEWAY_URL = baseURL .. "/api/__api_party/fictionzone"

--- Build gateway POST body
local function build_gateway_payload(innerPath, queryTable, extraHeaders)
	local qparts = {}
	for k, v in pairs(queryTable) do
		if type(v) == "boolean" then
			qparts[#qparts + 1] = '"' .. k .. '":' .. (v and "true" or "false")
		else
			qparts[#qparts + 1] = '"' .. k .. '":"' .. tostring(v) .. '"'
		end
	end
	local qstr = table.concat(qparts, ",")

	local hdrs = extraHeaders or {}
	local hparts = {}
	for _, h in ipairs(hdrs) do
		hparts[#hparts + 1] = '["' .. h[1] .. '","' .. h[2] .. '"]'
	end
	local hstr = table.concat(hparts, ",")

	return '{' ..
		'"path":"' .. innerPath .. '",' ..
		'"method":"GET",' ..
		'"query":{' .. qstr .. '},' ..
		'"headers":[' .. hstr .. ']' ..
		'}'
end

--- Call the gateway API and return response body as string
local function call_gateway(innerPath, queryTable, extraHeaders)
	local body = build_gateway_payload(innerPath, queryTable, extraHeaders)
	local reqBuilder = RequestBuilder()
	reqBuilder:url(GATEWAY_URL)
	reqBuilder:post(RequestBody(body, MediaType("application/json")))
	local headers = HeadersBuilder()
	headers:add("Content-Type", "application/json")
	headers:add("Accept", "application/json")
	headers:add("Origin", baseURL)
	headers:add("Referer", baseURL .. "/")
	reqBuilder:headers(headers:build())
	local req = reqBuilder:build()
	local resp = Request(req)
	local respBody = resp:body():string()
	return respBody
end

---------------------------------------------------------------------
--  Novel ID extraction from the novel detail page HTML
---------------------------------------------------------------------

local function extract_novel_id(html)
	-- 1) Check JSON-LD Book.identifier
	local ld = html:match('<script type="application/ld%+json">(.-)</script>')
	if ld then
		local ident = extract_json_str(ld, "identifier")
		if ident and ident:match("^%d+$") then
			return ident
		end
	end

	-- 2) Check __NUXT__ or similar state payloads
	local nuxt = html:match('__NUXT__%s*=%s*({.-});')
	if nuxt then
		local nid = extract_json_str(nuxt, "novel_id")
		if not nid then nid = extract_json_num(nuxt, "novel_id") end
		if nid and tostring(nid):match("^%d+$") then return tostring(nid) end
	end

	-- 3) Search for "novel_id":"<digits>" anywhere
	local nid = html:match('"novel[_]?[Ii][Dd]"%s*:%s*"(%d+)"')
	if nid then return nid end
	nid = html:match('"novel[_]?[Ii][Dd]"%s*:%s*(%d+)')
	if nid then return nid end

	-- 4) Search for data-novel-id attribute
	nid = html:match('data%-novel%-id%s*=%s*"(%-d+)"')
	if nid then return nid end

	return nil
end

---------------------------------------------------------------------
--  Cover image URL normalisation
---------------------------------------------------------------------

-- Replace listing-thumbnail size with decent cover size
local function normalize_cover_url(url)
	if not url then return "" end
	-- imgproxy path: /insecure/rs:fill:64:92/ → /insecure/rs:fill:300:400/
	url = url:gsub("rs:fill:%d+:%d+", "rs:fill:300:400")
	return url
end

---------------------------------------------------------------------
--  shrinkURL / expandURL
---------------------------------------------------------------------

-- Novel URL  →  just the slug path
-- Chapter URL  →  /novel_id/chapter_id
function shrinkURL(url, type)
	if type == KEY_NOVEL_URL then
		return url:gsub("https?://fictionzone%.net/novel/", "")
	end
	if type == KEY_CHAPTER_URL then
		return url
	end
	return url
end

function expandURL(url, type)
	if type == KEY_NOVEL_URL then
		if url:match("^https?://") then return url end
		return baseURL .. "/novel/" .. url
	end
	if type == KEY_CHAPTER_URL then
		return url
	end
	return url
end

---------------------------------------------------------------------
--  parseNovel  – novel detail + chapter list
---------------------------------------------------------------------

function parseNovel(novelURL)
	local fullURL = expandURL(novelURL, KEY_NOVEL_URL)
	local doc = GETDocument(fullURL)
	local html = doc:html()

	local novel_id = extract_novel_id(html)

	-- title
	local title = ""
	local t = doc:selectFirst("h1")
	if t then title = t:text() end
	if title == "" then
		t = doc:selectFirst("[class*='novel-title']")
		if t then title = t:text() end
	end

	-- cover
	local coverURL = ""
	local img = doc:selectFirst("img[src*='cdn.fictionzone.net']")
	if not img then img = doc:selectFirst("img[alt='" .. title .. "']") end
	if not img then img = doc:selectFirst("[class*='cover'] img") end
	if img then
		coverURL = img:attr("src") or ""
		coverURL = normalize_cover_url(coverURL)
	end

	-- author
	local author = ""
	local aLink = doc:selectFirst("a[href*='/profile/author/']")
	if aLink then author = aLink:text() end
	if author == "" then
		-- Try JSON-LD
		local ld = html:match('<script type="application/ld%+json">(.-)</script>')
		if ld then
			author = extract_json_str(ld, "author") or ""
		end
	end

	-- status
	local status = NovelStatus.UNKNOWN
	local statusText = html:match("Status.-<[^>]+>([^<]+)<")
	if not statusText then
		-- Try content after "Status" label
		statusText = html:match("Status</[^>]+>.-<[^>]+>([^<]+)<")
	end
	if statusText then
		local s = statusText:lower()
		if s:match("ongoing") or s:match("active") or s:match("continuing") then
			status = NovelStatus.ONGOING
		elseif s:match("completed") or s:match("finished") then
			status = NovelStatus.COMPLETED
		elseif s:match("hiatus") or s:match("dropped") or s:match("paused") then
			status = NovelStatus.HIATUS
		end
	end

	-- synopsis
	local synopsis = ""
	local synEl = doc:selectFirst("[class*='synopsis']")
	if synEl then synopsis = synEl:text() end
	if synopsis == "" then
		local ld = html:match('<script type="application/ld%+json">(.-)</script>')
		if ld then
			synopsis = extract_json_str(ld, "description") or ""
		end
	end

	-- chapters via gateway API
	local chapters = {}
	if novel_id then
		local page = 1
		local seen = {}
		while true do
			local respBody = call_gateway("/platform/chapter-lists", {
				novel_id = novel_id,
				page = page,
				page_size = 200
			})
			local dataObj = extract_data(respBody)
			if not dataObj then break end

			local batch = parse_chapter_list_entries(dataObj)
			local added = 0
			for _, ch in ipairs(batch) do
				if not seen[ch["id"]] then
					seen[ch["id"]] = true
					local chURL = novel_id .. "/" .. ch["id"]
					chapters[#chapters + 1] = NovelChapter({
						title = ch.title,
						link = chURL,
						order = ch.idx or #chapters + 1
					})
					added = added + 1
				end
			end
			if #batch == 0 or added == 0 then break end
			page = page + 1
		end
	end

	-- genres / tags
	local genres = {}
	local genreLinks = doc:select("a[href*='?genre=']")
	for i = 0, genreLinks:size() - 1 do
		local g = genreLinks:get(i)
		genres[#genres + 1] = g:text()
	end

	local tags = {}
	local tagLinks = doc:select("a[href*='?tag=']")
	for i = 0, tagLinks:size() - 1 do
		local tg = tagLinks:get(i)
		tags[#tags + 1] = tg:text()
	end

	return NovelInfo({
		title = title,
		coverURL = coverURL,
		author = author,
		status = status,
		synopsis = synopsis,
		genres = genres,
		tags = tags,
		chapters = chapters
	})
end

---------------------------------------------------------------------
--  getPassage  – chapter content
---------------------------------------------------------------------

function getPassage(chapterURL)
	-- chapterURL format: novel_id/chapter_id
	local novel_id, chapter_id = chapterURL:match("^(%d+)/(%d+)$")
	if not novel_id or not chapter_id then
		return "Invalid chapter URL"
	end

	local respBody = call_gateway("/platform/chapter-content", {
		novel_id = novel_id,
		chapter_id = chapter_id,
		highlight = true
	})

	local dataObj = extract_data(respBody)
	if not dataObj then
		return "Failed to load chapter content"
	end

	local title = extract_json_str(dataObj, "title") or ""
	local content = dataObj:match('"content":"(.-)"%s*[,}]')
	if not content then
		content = dataObj:match('"content"%s*:%s*"(.+)"%s*$')
	end

	if content then
		content = json_unescape(content)
	else
		return "Chapter content not available"
	end

	local html_output = "<h2>" .. title .. "</h2>"
	for line in content:gmatch("[^\r\n]+") do
		if line:match("^%s*$") then
			html_output = html_output .. "<br/>"
		else
			html_output = html_output .. "<p>" .. line .. "</p>"
		end
	end

	return html_output
end

---------------------------------------------------------------------
--  Listings
---------------------------------------------------------------------

local function scrape_listing_card(a)
	local href = a:attr("href") or ""
	if not href:match("/novel/") then
		return nil
	end

	local title = a:text():match("%S.+%S")
	if not title or title == "" then
		local h3 = a:selectFirst("h3")
		if h3 then title = h3:text() end
		local h2 = a:selectFirst("h2")
		if not title and h2 then title = h2:text() end
		local strong = a:selectFirst("[class*='title']")
		if not title and strong then title = strong:text() end
	end
	if not title or title == "" then return nil end

	local coverURL = ""
	local img = a:selectFirst("img[src*='cdn.fictionzone.net']")
	if img then
		coverURL = normalize_cover_url(img:attr("src") or "")
	end

	return Novel({
		title = title,
		link = href,
		coverURL = coverURL
	})
end

local function scrape_listing_page(pageURL)
	local doc = GETDocument(pageURL)
	local results = {}

	local links = doc:select("a[href*='/novel/']")
	local seen = {}
	for i = 0, links:size() - 1 do
		local a = links:get(i)
		local href = a:attr("href") or ""
		if href:match("/novel/") and not seen[href] then
			seen[href] = true
			local novel = scrape_listing_card(a)
			if novel then
				results[#results + 1] = novel
			end
		end
	end

	return results
end

local listings = {
	Listing("Trending Daily", true, function(data)
		local page = data[PAGE]
		local url = baseURL .. "/trending?page=" .. page
		return scrape_listing_page(url)
	end),
	Listing("Trending Weekly", true, function(data)
		local page = data[PAGE]
		local url = baseURL .. "/trending?period=weekly&page=" .. page
		return scrape_listing_page(url)
	end),
	Listing("Trending Monthly", true, function(data)
		local page = data[PAGE]
		local url = baseURL .. "/trending?period=monthly&page=" .. page
		return scrape_listing_page(url)
	end),
	Listing("Most Read", true, function(data)
		local page = data[PAGE]
		local url = baseURL .. "/most-read?page=" .. page
		return scrape_listing_page(url)
	end),
	Listing("Rising Stars", true, function(data)
		local page = data[PAGE]
		local url = baseURL .. "/rising-stars?page=" .. page
		return scrape_listing_page(url)
	end),
	Listing("Hidden Gems", true, function(data)
		local page = data[PAGE]
		local url = baseURL .. "/hidden-gems?page=" .. page
		return scrape_listing_page(url)
	end),
	Listing("Top Rated", true, function(data)
		local page = data[PAGE]
		local url = baseURL .. "/top-rated?page=" .. page
		return scrape_listing_page(url)
	end),
	Listing("Most Completed", true, function(data)
		local page = data[PAGE]
		local url = baseURL .. "/most-completed?page=" .. page
		return scrape_listing_page(url)
	end),
	Listing("Recently Added", true, function(data)
		local page = data[PAGE]
		local url = baseURL .. "/recently-added?page=" .. page
		return scrape_listing_page(url)
	end),
}

---------------------------------------------------------------------
--  Search
---------------------------------------------------------------------

function search(data)
	local page = data[PAGE] or 1
	local query = data[QUERY] or ""

	-- Try the browse page with search query param
	local q_encoded = query:gsub("%s+", "+")
	local url = baseURL .. "/browse?search=" .. q_encoded .. "&page=" .. page
	local doc = GETDocument(url)
	local results = {}

	local links = doc:select("a[href*='/novel/']")
	local seen = {}
	for i = 0, links:size() - 1 do
		local a = links:get(i)
		local href = a:attr("href") or ""
		if href:match("/novel/") and not seen[href] then
			seen[href] = true
			local novel = scrape_listing_card(a)
			if novel then
				results[#results + 1] = novel
			end
		end
	end

	return results
end

---------------------------------------------------------------------
--  Return
---------------------------------------------------------------------

return {
	id = id,
	name = name,
	baseURL = baseURL,
	listings = listings,
	getPassage = getPassage,
	parseNovel = parseNovel,
	shrinkURL = shrinkURL,
	expandURL = expandURL,

	imageURL = imageURL,
	hasCloudFlare = hasCloudFlare,
	hasSearch = hasSearch,
	isSearchIncrementing = isSearchIncrementing,
	chapterType = chapterType,
	startIndex = startIndex,

	search = search,
}
