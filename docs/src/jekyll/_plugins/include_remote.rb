require 'nokogiri'
require 'open-uri'
require 'uri'

class Jekyll::IncludeRemoteTag < Jekyll::Tags::IncludeTag
  @@remote_cache = {}

  def initialize(tag_name, markup, tokens)
    super
    @url = @file
  end



  def render(context)
    @url = render_variable(context) || @url

    xpath = false
    css = false

    if ! html = @@remote_cache["#{@url}_#{xpath}"]
      page = Nokogiri::HTML(open(@url))
      node = page.at_xpath(xpath) if xpath
      node = page.css(css) if css
      node = page if !node

      raise IOError.new "Error while parsing remote file '#{@url}': '#{xpath||css}' not found" if !node

      # cache result
      html = @@remote_cache["#{@url}_#{xpath}"] = node.to_s
    end

    begin
      partial = Liquid::Template.parse(html)

      context.stack do
        context['include'] = @params
        partial.render!(context)
      end
    rescue => e
      raise Jekyll::Tags::IncludeTagError.new e.message, @url
    end

  end

end

Liquid::Template.register_tag('include_remote', Jekyll::IncludeRemoteTag)
