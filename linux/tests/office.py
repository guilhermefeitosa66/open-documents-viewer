"""Office documents made on the fly for the tests (hand-written XML)."""

import zipfile

PNG = open(__file__.replace("office.py", "fixtures/images/a-red.png"), "rb").read()
WNS = ('xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
       'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
       'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
       'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
       'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"')


def _p(text, style=None, extra_ppr="", runs=None):
    ppr = (f'<w:pPr>{f"<w:pStyle w:val=\"{style}\"/>" if style else ""}{extra_ppr}</w:pPr>'
           if style or extra_ppr else "")
    body = runs if runs is not None else f"<w:r><w:t xml:space=\"preserve\">{text}</w:t></w:r>"
    return f"<w:p>{ppr}{body}</w:p>"


def make_docx(path):
    numbered = '<w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr>'
    bullet = '<w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>'
    body = "".join([
        _p("Report Title", "Title"),
        _p("Introduction", "Heading1"),
        _p(None, runs='<w:r><w:t xml:space="preserve">Plain </w:t></w:r>'
                      '<w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r>'
                      '<w:r><w:t xml:space="preserve">, </w:t></w:r>'
                      '<w:r><w:rPr><w:i/><w:color w:val="B5502B"/></w:rPr><w:t>red italic</w:t></w:r>'
                      '<w:r><w:t xml:space="preserve"> and </w:t></w:r>'
                      '<w:hyperlink r:id="rLink"><w:r><w:t>a link</w:t></w:r></w:hyperlink>'
                      '<w:r><w:t>.</w:t></w:r>'
                      '<w:r><w:footnoteReference w:id="1"/></w:r>'
                      '<w:r><w:fldChar w:fldCharType="begin"/></w:r>'
                      '<w:r><w:instrText>PAGE \\* MERGEFORMAT</w:instrText></w:r>'
                      '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
                      '<w:r><w:t>7</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>'),
        _p("Centered line", extra_ppr='<w:jc w:val="center"/>'),
        _p("First step", extra_ppr=numbered),
        _p("Second step", extra_ppr=numbered),
        _p("A bullet", extra_ppr=bullet),
        '<w:tbl><w:tr><w:trPr><w:tblHeader/></w:trPr>'
        '<w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Size</w:t></w:r></w:p></w:tc></w:tr>'
        '<w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>merged</w:t></w:r></w:p></w:tc></w:tr>'
        '</w:tbl>',
        _p(None, runs='<w:r><w:br w:type="page"/></w:r>'),
        _p("Details", "Heading2"),
        _p(None, runs='<w:r><w:drawing><wp:inline><a:graphic><a:graphicData><pic:pic><pic:blipFill>'
                      '<a:blip r:embed="rImg"/></pic:blipFill></pic:pic></a:graphicData></a:graphic>'
                      '</wp:inline></w:drawing></w:r>'),
        _p(None, runs='<w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden text</w:t></w:r>'),
        '<w:sectPr><w:headerReference w:type="default" r:id="rHeader"/>'
        '<w:footerReference w:type="default" r:id="rFooter"/></w:sectPr>',
    ])
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/'
                   'package/2006/content-types"/>')
        z.writestr("word/document.xml", f'<?xml version="1.0"?><w:document {WNS}><w:body>{body}</w:body></w:document>')
        z.writestr("word/styles.xml", f'<?xml version="1.0"?><w:styles {WNS}>'
                   '<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>'
                   '<w:style w:styleId="Title"><w:name w:val="Title"/></w:style>'
                   '<w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>'
                   '<w:style w:styleId="Heading2"><w:name w:val="heading 2"/></w:style></w:styles>')
        z.writestr("word/numbering.xml", f'<?xml version="1.0"?><w:numbering {WNS}>'
                   '<w:abstractNum w:abstractNumId="10"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/>'
                   '<w:lvlText w:val="•"/></w:lvl></w:abstractNum>'
                   '<w:abstractNum w:abstractNumId="20"><w:lvl w:ilvl="0"><w:start w:val="1"/>'
                   '<w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>'
                   '<w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num>'
                   '<w:num w:numId="2"><w:abstractNumId w:val="20"/></w:num></w:numbering>')
        z.writestr("word/footnotes.xml", f'<?xml version="1.0"?><w:footnotes {WNS}>'
                   '<w:footnote w:type="separator" w:id="-1"><w:p><w:r><w:t>---</w:t></w:r></w:p></w:footnote>'
                   '<w:footnote w:id="1"><w:p><w:r><w:t>A footnote.</w:t></w:r></w:p></w:footnote></w:footnotes>')
        z.writestr("word/header1.xml", f'<?xml version="1.0"?><w:hdr {WNS}><w:p><w:r><w:t>Company header</w:t></w:r>'
                   '</w:p></w:hdr>')
        z.writestr("word/footer1.xml", f'<?xml version="1.0"?><w:ftr {WNS}><w:p><w:r><w:t>Page footer</w:t></w:r>'
                   '</w:p></w:ftr>')
        z.writestr("word/media/image1.png", PNG)
        z.writestr("word/_rels/document.xml.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.'
                   'openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rLink" Type="hyperlink" Target="https://libre-apps.org" TargetMode="External"/>'
                   '<Relationship Id="rImg" Type="image" Target="media/image1.png"/>'
                   '<Relationship Id="rHeader" Type="header" Target="header1.xml"/>'
                   '<Relationship Id="rFooter" Type="footer" Target="footer1.xml"/></Relationships>')
    return path


ODF_NS = ('xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
          'xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" '
          'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" '
          'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" '
          'xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" '
          'xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" '
          'xmlns:xlink="http://www.w3.org/1999/xlink"')


def make_odt(path):
    content = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-content {ODF_NS}>
<office:automatic-styles>
  <style:style style:name="B" style:family="text"><style:text-properties fo:font-weight="bold"/></style:style>
  <style:style style:name="IR" style:family="text"><style:text-properties fo:font-style="italic" fo:color="#b5502b"/></style:style>
  <style:style style:name="H" style:family="text"><style:text-properties text:display="none"/></style:style>
  <style:style style:name="C" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/></style:style>
  <style:style style:name="PB" style:family="paragraph"><style:paragraph-properties fo:break-before="page"/></style:style>
  <text:list-style style:name="Num"><text:list-level-style-number text:level="1" style:num-format="1" style:num-suffix="."/></text:list-style>
  <text:list-style style:name="Bul"><text:list-level-style-bullet text:level="1" text:bullet-char="•"/></text:list-style>
</office:automatic-styles>
<office:body><office:text>
  <text:h text:outline-level="1">Report Title</text:h>
  <text:h text:outline-level="2">Introduction</text:h>
  <text:p>Plain <text:span text:style-name="B">bold</text:span>, <text:span text:style-name="IR">red italic</text:span> and <text:a xlink:href="https://libre-apps.org">a link</text:a>.<text:note text:note-class="footnote"><text:note-citation>1</text:note-citation><text:note-body><text:p>A footnote.</text:p></text:note-body></text:note></text:p>
  <text:p text:style-name="C">Centered<text:s text:c="2"/>line</text:p>
  <text:list text:style-name="Num"><text:list-item><text:p>First step</text:p></text:list-item><text:list-item><text:p>Second step</text:p></text:list-item></text:list>
  <text:list text:style-name="Bul"><text:list-item><text:p>A bullet</text:p></text:list-item></text:list>
  <table:table><table:table-row><table:table-cell><text:p>Name</text:p></table:table-cell><table:table-cell><text:p>Size</text:p></table:table-cell></table:table-row>
  <table:table-row><table:table-cell table:number-columns-spanned="2"><text:p>merged</text:p></table:table-cell><table:covered-table-cell/></table:table-row></table:table>
  <text:h text:style-name="PB" text:outline-level="2">Details</text:h>
  <text:p><draw:frame><draw:image xlink:href="Pictures/red.png"/></draw:frame></text:p>
  <text:p><text:span text:style-name="H">hidden text</text:span></text:p>
</office:text></office:body></office:document-content>"""
    styles = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles {ODF_NS}><office:master-styles><style:master-page style:name="Standard">
<style:header><text:p>Company header</text:p></style:header><style:footer><text:p>Page footer</text:p></style:footer>
</style:master-page></office:master-styles></office:document-styles>"""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/vnd.oasis.opendocument.text")
        z.writestr("content.xml", content)
        z.writestr("styles.xml", styles)
        z.writestr("Pictures/red.png", PNG)
    return path


SS = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ' \
     'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'


def make_xlsx(path, extra_rows=0):
    rows = [
        '<row r="1"><c r="A1" t="s" s="1"><v>0</v></c><c r="B1" t="s" s="1"><v>1</v></c>'
        '<c r="C1" t="s" s="1"><v>2</v></c><c r="D1" t="inlineStr"><is><t>secret col</t></is></c></row>',
        '<row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" s="2"><v>1234.5</v></c><c r="C2" s="3"><v>45000</v></c></row>',
        '<row r="3" hidden="1"><c r="A3" t="inlineStr"><is><t>hidden row</t></is></c></row>',
        '<row r="4"><c r="A4" t="inlineStr"><is><t>Total</t></is></c><c r="B4" s="2"><f>SUM(B2:B2)</f><v>1234.5</v></c>'
        '<c r="C4" t="b"><v>1</v></c></row>',
        '<row r="5"><c r="A5" t="e"><v>#DIV/0!</v></c><c r="B5" t="inlineStr"><is><t>merged here</t></is></c></row>',
    ]
    rows += [f'<row r="{6 + i}"><c r="A{6 + i}"><v>{i}</v></c></row>' for i in range(extra_rows)]
    sheet1 = (f'<?xml version="1.0"?><worksheet {SS}><dimension ref="A1:D{5 + extra_rows}"/>'
              '<sheetViews><sheetView><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews>'
              '<cols><col min="1" max="1" width="20"/><col min="4" max="4" hidden="1"/></cols>'
              f'<sheetData>{"".join(rows)}</sheetData>'
              '<mergeCells><mergeCell ref="B5:C5"/></mergeCells></worksheet>')
    sheet2 = f'<?xml version="1.0"?><worksheet {SS}><sheetData><row r="1"><c r="A1"><v>7</v></c></row></sheetData></worksheet>'
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("xl/workbook.xml", f'<?xml version="1.0"?><workbook {SS}><sheets>'
                   '<sheet name="Sales" sheetId="1" r:id="rId1"/><sheet name="Hidden" sheetId="2" r:id="rId2" state="hidden"/>'
                   '</sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.'
                   'openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/>'
                   '<Relationship Id="rId2" Type="worksheet" Target="worksheets/sheet2.xml"/></Relationships>')
        z.writestr("xl/sharedStrings.xml", f'<?xml version="1.0"?><sst {SS}><si><t>Item</t></si><si><t>Price</t></si>'
                   '<si><t>Date</t></si><si><r><t>Ri</t></r><r><t>ch</t></r></si></sst>')
        z.writestr("xl/styles.xml", f'<?xml version="1.0"?><styleSheet {SS}>'
                   '<numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/></numFmts>'
                   '<fonts count="2"><font/><font><b/><color rgb="FFB5502B"/></font></fonts>'
                   '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/>'
                   '</fill><fill><patternFill patternType="solid"><fgColor rgb="FFF5D7A1"/></patternFill></fill></fills>'
                   '<borders count="1"><border/></borders>'
                   '<cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0"/><xf numFmtId="0" fontId="1" fillId="2"/>'
                   '<xf numFmtId="4" fontId="0" fillId="0"/><xf numFmtId="164" fontId="0" fillId="0"/></cellXfs>'
                   '</styleSheet>')
        z.writestr("xl/worksheets/sheet1.xml", sheet1)
        z.writestr("xl/worksheets/sheet2.xml", sheet2)
    return path


def make_ods(path):
    content = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-content {ODF_NS} xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0">
<office:automatic-styles>
  <style:style style:name="co1" style:family="table-column"><style:table-column-properties style:column-width="4cm"/></style:style>
  <style:style style:name="ta2" style:family="table"><style:table-properties table:display="false"/></style:style>
  <style:style style:name="ce1" style:family="table-cell"><style:table-cell-properties fo:background-color="#f5d7a1"/>
    <style:text-properties fo:font-weight="bold" fo:color="#b5502b"/></style:style>
</office:automatic-styles>
<office:body><office:spreadsheet>
<table:table table:name="Sales">
  <table:table-column table:style-name="co1"/><table:table-column table:number-columns-repeated="2"/>
  <table:table-column table:visibility="collapse"/>
  <table:table-row>
    <table:table-cell table:style-name="ce1" office:value-type="string"><text:p>Item</text:p></table:table-cell>
    <table:table-cell table:style-name="ce1" office:value-type="string"><text:p>Price</text:p></table:table-cell>
    <table:table-cell table:style-name="ce1" office:value-type="string"><text:p>Date</text:p></table:table-cell>
    <table:table-cell office:value-type="string"><text:p>secret col</text:p></table:table-cell>
  </table:table-row>
  <table:table-row>
    <table:table-cell office:value-type="string"><text:p>Rich</text:p></table:table-cell>
    <table:table-cell office:value-type="currency" office:value="1234.5"><text:p>R$ 1.234,50</text:p></table:table-cell>
    <table:table-cell office:value-type="date" office:date-value="2023-03-15"><text:p>15/03/2023</text:p></table:table-cell>
  </table:table-row>
  <table:table-row table:visibility="collapse"><table:table-cell office:value-type="string"><text:p>hidden row</text:p></table:table-cell></table:table-row>
  <table:table-row>
    <table:table-cell office:value-type="string"><text:p>Total</text:p></table:table-cell>
    <table:table-cell table:formula="of:=SUM([.B2:.B2])" office:value-type="currency" office:value="1234.5"><text:p>R$ 1.234,50</text:p></table:table-cell>
    <table:table-cell office:value-type="boolean" office:boolean-value="true"><text:p>VERDADEIRO</text:p></table:table-cell>
  </table:table-row>
  <table:table-row>
    <table:table-cell table:formula="of:=1/0" office:value-type="string"><text:p>#DIV/0!</text:p></table:table-cell>
    <table:table-cell table:number-columns-spanned="2" office:value-type="string"><text:p>merged here</text:p></table:table-cell>
    <table:covered-table-cell/>
  </table:table-row>
  <table:table-row table:number-rows-repeated="1048570"><table:table-cell table:number-columns-repeated="1024"/></table:table-row>
</table:table>
<table:table table:name="Hidden" table:style-name="ta2"><table:table-row><table:table-cell office:value-type="float" office:value="7"><text:p>7</text:p></table:table-cell></table:table-row></table:table>
</office:spreadsheet></office:body></office:document-content>"""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/vnd.oasis.opendocument.spreadsheet")
        z.writestr("content.xml", content)
    return path


def make_xls(path):
    """An .xls written with xlwt (a test-only dependency)."""
    import datetime

    import xlwt

    wb = xlwt.Workbook()
    ws = wb.add_sheet("Sales")
    hidden = wb.add_sheet("Hidden")
    hidden.visibility = 1
    hidden.write(0, 0, 7)
    head = xlwt.easyxf("font: bold on, colour red; pattern: pattern solid, fore_colour light_yellow")
    money = xlwt.easyxf(num_format_str="#,##0.00")
    date = xlwt.easyxf(num_format_str="dd/mm/yyyy")
    for c, title in enumerate(["Item", "Price", "Date"]):
        ws.write(0, c, title, head)
    ws.write(1, 0, "Rich")
    ws.write(1, 1, 1234.5, money)
    ws.write(1, 2, datetime.date(2023, 3, 15), date)
    ws.write(2, 0, "hidden row")
    ws.row(2).hidden = True
    ws.write(3, 0, "Total")
    ws.write(3, 1, xlwt.Formula("SUM(B2:B2)"), money)
    ws.write(3, 2, True)
    ws.write_merge(4, 4, 1, 2, "merged here")
    ws.write(0, 3, "secret col")
    ws.col(3).hidden = True
    ws.col(0).width = 20 * 256
    wb.save(str(path))
    return path


PNS = ('xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
       'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
       'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"')
RELS = '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
RT = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"


def _rels(*items):
    return RELS + "".join(f'<Relationship Id="{i}" Type="{RT}{t}" Target="{target}"/>'
                          for i, t, target in items) + "</Relationships>"


def _ph_sp(kind, idx=None, xfrm="", body="<a:p/>", lst=""):
    idx_attr = f' idx="{idx}"' if idx is not None else ""
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="2" name="{kind}"/><p:cNvSpPr/><p:nvPr><p:ph type="{kind}"{idx_attr}/>'
            f'</p:nvPr></p:nvSpPr><p:spPr>{xfrm}</p:spPr><p:txBody><a:bodyPr/><a:lstStyle>{lst}</a:lstStyle>'
            f'{body}</p:txBody></p:sp>')


def _xfrm(x, y, w, h):
    return f'<a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{w}" cy="{h}"/></a:xfrm>'


def make_pptx(path):
    """Three slides (one hidden): a title slide inheriting from the layout,
    a slide with bullets, a shape, a picture, a table and notes."""
    tree = '<p:cSld>{bg}<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>' \
           '<p:grpSpPr/>{shapes}</p:spTree></p:cSld>'
    master = (f'<?xml version="1.0"?><p:sldMaster {PNS}>'
              + tree.format(bg='<p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>',
                            shapes=_ph_sp("title", xfrm=_xfrm(457200, 274638, 8229600, 1143000))
                            + _ph_sp("body", 1, xfrm=_xfrm(457200, 1600200, 8229600, 4525963)))
              + '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" '
                'accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" '
                'folHlink="folHlink"/>'
              '<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4000"/></a:lvl1pPr></p:titleStyle>'
              '<p:bodyStyle><a:lvl1pPr><a:defRPr sz="2400"/></a:lvl1pPr><a:lvl2pPr><a:defRPr sz="2000"/></a:lvl2pPr>'
              '</p:bodyStyle></p:txStyles></p:sldMaster>')
    layout = (f'<?xml version="1.0"?><p:sldLayout {PNS}>'
              + tree.format(bg="", shapes=_ph_sp("ctrTitle", xfrm=_xfrm(685800, 2130425, 7772400, 1470025),
                                                 lst='<a:lvl1pPr algn="ctr"/>')
                            + _ph_sp("body", 1))
              + "</p:sldLayout>")
    theme = ('<?xml version="1.0"?><a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
             'name="T"><a:themeElements><a:clrScheme name="T"><a:dk1><a:sysClr val="windowText" lastClr="000000"/>'
             '</a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F497D"/></a:dk2>'
             '<a:lt2><a:srgbClr val="EEECE1"/></a:lt2><a:accent1><a:srgbClr val="B5502B"/></a:accent1>'
             '</a:clrScheme><a:fontScheme name="T"><a:majorFont><a:latin typeface="DejaVu Serif"/></a:majorFont>'
             '<a:minorFont><a:latin typeface="DejaVu Sans"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>')
    run = '<a:r><a:rPr lang="en-US"{attrs}/><a:t>{text}</a:t></a:r>'
    slide1 = (f'<?xml version="1.0"?><p:sld {PNS}>'
              + tree.format(bg="", shapes=_ph_sp("ctrTitle", body=f'<a:p>{run.format(attrs="", text="Quarterly Review")}</a:p>')
                            + _ph_sp("body", 1, body=f'<a:p><a:pPr><a:buNone/></a:pPr>'
                                     f'{run.format(attrs="", text="Hello &amp; welcome")}</a:p>'))
              + "</p:sld>")
    table = ('<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="6" name="t"/><p:cNvGraphicFramePr/><p:nvPr/>'
             '</p:nvGraphicFramePr><p:xfrm><a:off x="457200" y="4800600"/><a:ext cx="4000000" cy="740000"/></p:xfrm>'
             '<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl>'
             '<a:tblGrid><a:gridCol w="2000000"/><a:gridCol w="2000000"/></a:tblGrid>'
             '<a:tr h="370000"><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>Region</a:t></a:r></a:p></a:txBody></a:tc>'
             '<a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>Sales</a:t></a:r></a:p></a:txBody></a:tc></a:tr>'
             '<a:tr h="370000"><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>North</a:t></a:r></a:p></a:txBody></a:tc>'
             '<a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>42</a:t></a:r></a:p></a:txBody></a:tc></a:tr>'
             '</a:tbl></a:graphicData></a:graphic></p:graphicFrame>')
    shape = ('<p:sp><p:nvSpPr><p:cNvPr id="4" name="box"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>'
             + _xfrm(5000000, 1600200, 3000000, 1500000)
             + '<a:prstGeom prst="roundRect"><a:avLst/></a:prstGeom><a:solidFill><a:schemeClr val="accent1">'
               '<a:lumMod val="75000"/></a:schemeClr></a:solidFill><a:ln w="25400"><a:solidFill>'
               '<a:srgbClr val="000000"/></a:solidFill></a:ln></p:spPr><p:txBody><a:bodyPr anchor="ctr"/><a:p>'
               '<a:pPr algn="ctr"/>' + run.format(attrs=' b="1" sz="2000"', text="Callout") + '</a:p></p:txBody></p:sp>')
    pic = ('<p:pic><p:nvPicPr><p:cNvPr id="5" name="pic"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill>'
           '<a:blip r:embed="rImg"/></p:blipFill><p:spPr>' + _xfrm(5000000, 3300000, 1000000, 1000000)
           + '</p:spPr></p:pic>')
    group = ('<p:grpSp><p:nvGrpSpPr><p:cNvPr id="7" name="g"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr>'
             '<a:xfrm><a:off x="7000000" y="3300000"/><a:ext cx="1000000" cy="1000000"/>'
             '<a:chOff x="0" y="0"/><a:chExt cx="2000000" cy="2000000"/></a:xfrm></p:grpSpPr>'
             '<p:sp><p:nvSpPr><p:cNvPr id="8" name="dot"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>'
             + _xfrm(1000000, 1000000, 1000000, 1000000)
             + '<a:prstGeom prst="ellipse"/><a:solidFill><a:srgbClr val="00AA00"/></a:solidFill></p:spPr></p:sp>'
             '</p:grpSp>')
    bullets = ('<a:p>' + run.format(attrs="", text="First point") + '</a:p>'
               '<a:p><a:pPr lvl="1"/>' + run.format(attrs=' i="1"', text="Detail") + '</a:p>'
               '<a:p><a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr>' + run.format(attrs="", text="One") + '</a:p>'
               '<a:p><a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr>'
               + run.format(attrs=' u="sng"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></a:rPr>'
                            '<a:t>Two</a:t></a:r><a:r><a:rPr', text=" red") + '</a:p>')
    slide2 = (f'<?xml version="1.0"?><p:sld {PNS}>'
              + tree.format(bg='<p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFF5E0"/></a:solidFill></p:bgPr></p:bg>',
                            shapes=_ph_sp("title", body=f'<a:p>{run.format(attrs="", text="Results")}</a:p>')
                            + _ph_sp("body", 1, xfrm=_xfrm(457200, 1600200, 4400000, 3000000), body=bullets)
                            + shape + pic + table + group)
              + "</p:sld>")
    slide3 = (f'<?xml version="1.0"?><p:sld {PNS} show="0">'
              + tree.format(bg="", shapes=_ph_sp("title", body=f'<a:p>{run.format(attrs="", text="Backup")}</a:p>'))
              + "</p:sld>")
    notes = (f'<?xml version="1.0"?><p:notes {PNS}>'
             + tree.format(bg="", shapes=_ph_sp("sldImg") + _ph_sp("body", 1, body='<a:p><a:r><a:t>Mention the '
                                                                                   'north.</a:t></a:r></a:p>'))
             + "</p:notes>")
    presentation = (f'<?xml version="1.0"?><p:presentation {PNS}><p:sldMasterIdLst><p:sldMasterId id="2147483648" '
                    'r:id="rM"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rS1"/>'
                    '<p:sldId id="257" r:id="rS2"/><p:sldId id="258" r:id="rS3"/></p:sldIdLst>'
                    '<p:sldSz cx="9144000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>')
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/'
                   'package/2006/content-types"/>')
        z.writestr("ppt/presentation.xml", presentation)
        z.writestr("ppt/_rels/presentation.xml.rels", _rels(("rM", "slideMaster", "slideMasters/slideMaster1.xml"),
                                                            ("rS1", "slide", "slides/slide1.xml"),
                                                            ("rS2", "slide", "slides/slide2.xml"),
                                                            ("rS3", "slide", "slides/slide3.xml")))
        z.writestr("ppt/slideMasters/slideMaster1.xml", master)
        z.writestr("ppt/slideMasters/_rels/slideMaster1.xml.rels", _rels(("rT", "theme", "../theme/theme1.xml"),
                                                                         ("rL", "slideLayout",
                                                                          "../slideLayouts/slideLayout1.xml")))
        z.writestr("ppt/theme/theme1.xml", theme)
        z.writestr("ppt/slideLayouts/slideLayout1.xml", layout)
        z.writestr("ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                   _rels(("rM", "slideMaster", "../slideMasters/slideMaster1.xml")))
        lay = ("rL", "slideLayout", "../slideLayouts/slideLayout1.xml")
        z.writestr("ppt/slides/slide1.xml", slide1)
        z.writestr("ppt/slides/_rels/slide1.xml.rels", _rels(lay))
        z.writestr("ppt/slides/slide2.xml", slide2)
        z.writestr("ppt/slides/_rels/slide2.xml.rels", _rels(lay, ("rImg", "image", "../media/image1.png"),
                                                             ("rN", "notesSlide", "../notesSlides/notesSlide2.xml")))
        z.writestr("ppt/slides/slide3.xml", slide3)
        z.writestr("ppt/slides/_rels/slide3.xml.rels", _rels(lay))
        z.writestr("ppt/notesSlides/notesSlide2.xml", notes)
        z.writestr("ppt/media/image1.png", PNG)
    return path


ODP_NS = ODF_NS + (' xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" '
                   'xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"')


def make_odp(path, flat=False):
    """Three pages (one hidden) like make_pptx: a title page with placeholders
    positioned by the master, a page with a list, shapes, a picture, a
    table, a group and notes."""
    styles = f"""<office:font-face-decls><style:font-face style:name="DV" svg:font-family="'DejaVu Sans'"/></office:font-face-decls>
<office:styles>
  <style:default-style style:family="graphic"><style:graphic-properties draw:fill-color="#729fcf"/></style:default-style>
  <style:style style:name="Default-title" style:family="presentation"><style:graphic-properties draw:fill="none" draw:stroke="none"/>
    <style:paragraph-properties fo:text-align="center"/><style:text-properties fo:font-size="40pt" style:font-name="DV"/></style:style>
  <style:style style:name="Default-outline1" style:family="presentation"><style:graphic-properties draw:fill="none" draw:stroke="none">
    <text:list-style style:name="L"><text:list-level-style-bullet text:level="1" text:bullet-char="●"/>
    <text:list-level-style-bullet text:level="2" text:bullet-char="–"/></text:list-style></style:graphic-properties>
    <style:text-properties fo:font-size="24pt"/></style:style>
</office:styles>
<office:automatic-styles>
  <style:page-layout style:name="PM1"><style:page-layout-properties fo:page-width="28cm" fo:page-height="15.75cm"/></style:page-layout>
  <style:style style:name="Mdp1" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="#ffffff"/></style:style>
  <style:style style:name="Mgr1" style:family="graphic"><style:graphic-properties draw:fill="solid" draw:fill-color="#b5502b" draw:stroke="none"/></style:style>
</office:automatic-styles>
<office:master-styles><style:master-page style:name="Default" style:page-layout-name="PM1" draw:style-name="Mdp1">
  <draw:frame presentation:style-name="Default-title" presentation:class="title" svg:x="1.4cm" svg:y="0.6cm" svg:width="25.2cm" svg:height="2.6cm" presentation:placeholder="true"><draw:text-box/></draw:frame>
  <draw:frame presentation:style-name="Default-outline1" presentation:class="outline" svg:x="1.4cm" svg:y="3.7cm" svg:width="25.2cm" svg:height="9.5cm" presentation:placeholder="true"><draw:text-box/></draw:frame>
  <draw:rect draw:style-name="Mgr1" svg:x="0cm" svg:y="15cm" svg:width="28cm" svg:height="0.75cm"/>
</style:master-page></office:master-styles>"""
    content = f"""<office:automatic-styles>
  <style:style style:name="dp2" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="#fff5e0"/></style:style>
  <style:style style:name="dp3" style:family="drawing-page"><style:drawing-page-properties presentation:visibility="hidden"/></style:style>
  <style:style style:name="gr1" style:family="graphic"><style:graphic-properties draw:fill="solid" draw:fill-color="#883c20" svg:stroke-color="#000000" svg:stroke-width="0.07cm" draw:textarea-vertical-align="middle"/></style:style>
  <style:style style:name="gr2" style:family="graphic"><style:graphic-properties draw:fill="solid" draw:fill-color="#00aa00" draw:stroke="none"/></style:style>
  <style:style style:name="T1" style:family="text"><style:text-properties fo:font-weight="bold"/></style:style>
  <style:style style:name="T2" style:family="text"><style:text-properties fo:color="#ff0000" style:text-underline-style="solid"/></style:style>
  <style:style style:name="T3" style:family="text"><style:text-properties fo:font-style="italic"/></style:style>
  <text:list-style style:name="N"><text:list-level-style-number text:level="1" style:num-format="1" style:num-suffix="."/></text:list-style>
</office:automatic-styles>
<office:body><office:presentation>
<draw:page draw:name="p1" draw:master-page-name="Default">
  <draw:frame presentation:style-name="Default-title" presentation:class="title"><draw:text-box><text:p>Quarterly Review</text:p></draw:text-box></draw:frame>
  <draw:frame presentation:class="subtitle" svg:x="1.4cm" svg:y="5cm" svg:width="25.2cm" svg:height="3cm"><draw:text-box><text:p>Hello &amp; welcome</text:p></draw:text-box></draw:frame>
</draw:page>
<draw:page draw:name="p2" draw:style-name="dp2" draw:master-page-name="Default">
  <draw:frame presentation:style-name="Default-title" presentation:class="title"><draw:text-box><text:p>Results</text:p></draw:text-box></draw:frame>
  <draw:frame presentation:style-name="Default-outline1" presentation:class="outline" svg:x="1.4cm" svg:y="3.7cm" svg:width="12cm" svg:height="8cm"><draw:text-box>
    <text:list><text:list-item><text:p>First point</text:p><text:list><text:list-item><text:p><text:span text:style-name="T3">Detail</text:span></text:p></text:list-item></text:list></text:list-item></text:list>
    <text:list text:style-name="N"><text:list-item><text:p>One</text:p></text:list-item><text:list-item><text:p><text:span text:style-name="T2">Two</text:span> red</text:p></text:list-item></text:list>
  </draw:text-box></draw:frame>
  <draw:custom-shape draw:style-name="gr1" svg:x="15cm" svg:y="3.7cm" svg:width="8cm" svg:height="4cm"><text:p><text:span text:style-name="T1">Callout</text:span></text:p><draw:enhanced-geometry draw:type="round-rectangle"/></draw:custom-shape>
  <draw:frame svg:x="15cm" svg:y="8.5cm" svg:width="2.5cm" svg:height="2.5cm"><draw:image xlink:href="Pictures/red.png"/></draw:frame>
  <draw:frame svg:x="1.4cm" svg:y="12cm" svg:width="10cm" svg:height="2cm"><table:table>
    <table:table-row><table:table-cell><text:p>Region</text:p></table:table-cell><table:table-cell><text:p>Sales</text:p></table:table-cell></table:table-row>
    <table:table-row><table:table-cell><text:p>North</text:p></table:table-cell><table:table-cell><text:p>42</text:p></table:table-cell></table:table-row>
  </table:table></draw:frame>
  <draw:g><draw:ellipse draw:style-name="gr2" svg:x="20cm" svg:y="9cm" svg:width="1.5cm" svg:height="1.5cm"/></draw:g>
  <draw:line svg:x1="1cm" svg:y1="3.4cm" svg:x2="27cm" svg:y2="3.4cm"/>
  <presentation:notes><draw:page-thumbnail presentation:class="page"/><draw:frame presentation:class="notes"><draw:text-box><text:p>Mention the north.</text:p></draw:text-box></draw:frame></presentation:notes>
</draw:page>
<draw:page draw:name="p3" draw:style-name="dp3" draw:master-page-name="Default">
  <draw:frame presentation:style-name="Default-title" presentation:class="title"><draw:text-box><text:p>Backup</text:p></draw:text-box></draw:frame>
</draw:page>
</office:presentation></office:body>"""
    if flat:
        head, rest = styles.split("<office:automatic-styles>")
        style_auto, masters = rest.split("</office:automatic-styles>")
        content_auto, body = content.split("<office:automatic-styles>")[1].split("</office:automatic-styles>")
        with open(path, "w", encoding="utf-8") as f:
            f.write(f'<?xml version="1.0" encoding="UTF-8"?>\n<office:document {ODP_NS} '
                    f'office:mimetype="application/vnd.oasis.opendocument.presentation">{head}'
                    f"<office:automatic-styles>{style_auto}{content_auto}</office:automatic-styles>"
                    f"{masters}{body}</office:document>")
        return path
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/vnd.oasis.opendocument.presentation")
        z.writestr("content.xml", f'<?xml version="1.0" encoding="UTF-8"?>\n<office:document-content {ODP_NS}>'
                                  f"{content}</office:document-content>")
        z.writestr("styles.xml", f'<?xml version="1.0" encoding="UTF-8"?>\n<office:document-styles {ODP_NS}>'
                                 f"{styles}</office:document-styles>")
        z.writestr("Pictures/red.png", PNG)
    return path


def make_odg(path):
    """An OpenDocument drawing: make_odp's pages under office:drawing."""
    make_odp(path)
    with zipfile.ZipFile(path) as z:
        parts = {n: z.read(n) for n in z.namelist()}
    parts["content.xml"] = parts["content.xml"].replace(b"office:presentation>", b"office:drawing>")
    parts["mimetype"] = b"application/vnd.oasis.opendocument.graphics"
    with zipfile.ZipFile(path, "w") as z:
        for name, data in parts.items():
            z.writestr(name, data)
    return path


def make_iwork(path, preview=True):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("Index/Document.iwa", b"\0" * 16)
        if preview:
            z.writestr("preview.jpg", PNG)
    return path


def encrypt_ooxml(plain, path, password):
    """``plain`` encrypted with ``password`` (ECMA-376 agile encryption)."""
    from cfb import encrypt_ooxml as encrypt

    return encrypt(plain, path, password)


def make_encrypted_odt(path):
    make_odt(path)
    with zipfile.ZipFile(path, "a") as z:
        z.writestr("META-INF/manifest.xml", '<?xml version="1.0"?><manifest:manifest xmlns:manifest="urn:oasis:'
                   'names:tc:opendocument:xmlns:manifest:1.0"><manifest:file-entry manifest:full-path="content.xml">'
                   '<manifest:encryption-data manifest:checksum-type="SHA1"/></manifest:file-entry>'
                   '</manifest:manifest>')
    return path
