/* Native vector renderer. It renders SVG source; it never edits the generated raster reference. */
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const sharp = require('sharp');
const GREEN='#173E35', GOLD='#B89B5C', IVORY='#F6F5EF';
const title='المطوف الذكي', slogan='رفيقٌ وفيّ، وسندٌ قويّ.';
const symbol = fs.readFileSync(path.join(__dirname,'smart-mutawwif-symbol.svg'),'utf8');
const paths = [...symbol.matchAll(/<path\b[^>]*\/>/g)].map(x=>x[0]).join('\n');
if ((paths.match(/<path/g)||[]).length!==5) throw new Error('Expected five native symbol paths');
const put=(name,value)=>{const file=path.join(root,name);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,value);return file;};
const svg=(w,h,body,defs='')=>`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img" aria-label="${title}">${defs}${body}</svg>`;
const outlinedType=fs.readFileSync(path.join(__dirname,'wordmark-paths.svg'),'utf8').replace(/^<svg[^>]*>/,'').replace(/<\/svg>$/,'');
const full=(light=false)=>svg(1200,900,`<g transform="translate(254.4 -110) scale(6.4)">${light?paths.replaceAll(GREEN,IVORY):paths}</g>${light?outlinedType.replaceAll(GREEN,IVORY):outlinedType}`);
const fullSvg=full(), lightSvg=full(true);
put('source/smart-mutawwif-logo.svg',fullSvg);
put('source/smart-mutawwif-logo-on-dark.svg',lightSvg);
const compact=`<svg xmlns="http://www.w3.org/2000/svg" width="76" height="76" viewBox="16 16 76 76" role="img" aria-label="${title}">${paths}</svg>`;
put('source/smart-mutawwif-symbol-compact.svg',compact);
put('source/smart-mutawwif-symbol-monochrome.svg',symbol.replaceAll(GREEN,'#FFFFFF').replaceAll(GOLD,'#FFFFFF'));
put('source/brand-tokens.json',JSON.stringify({productName:title,slogan,team:'سعي',colors:{green:GREEN,gold:GOLD,ivory:IVORY},interfaceFont:'IBM Plex Sans Arabic',approvedFor:['التطبيق','العرض التقديمي'],sourceDate:'2026-09-08'},null,2));
async function render(input,name,width){const file=path.join(root,name);fs.mkdirSync(path.dirname(file),{recursive:true});await sharp(Buffer.from(input),{density:300}).resize({width}).png().toFile(file);}
async function main(){
  await render(fullSvg,'png/smart-mutawwif-logo.png',1600);
  await render(fullSvg,'png/smart-mutawwif-logo-3200.png',3200);
  await render(lightSvg,'png/smart-mutawwif-logo-on-dark.png',1600);
  for(const size of [32,48,64,96,128,192,256,512,1024]) await render(compact,`png/symbol/smart-mutawwif-symbol-${size}.png`,size);
  await render(symbol,'png/smart-mutawwif-adaptive-foreground.png',432);
  const rootless=fullSvg.replace(/^<svg[^>]*>/,'').replace(/<\/svg>$/,'');
  const darkRootless=lightSvg.replace(/^<svg[^>]*>/,'').replace(/<\/svg>$/,'');
  await render(svg(3840,2160,`<rect width="3840" height="2160" fill="${IVORY}"/><g transform="translate(720 135) scale(2)">${rootless}</g>`),'presentation/smart-mutawwif-light-4k.png',3840);
  await render(svg(3840,2160,`<rect width="3840" height="2160" fill="${GREEN}"/><g transform="translate(720 135) scale(2)">${darkRootless}</g>`),'presentation/smart-mutawwif-dark-4k.png',3840);
  const background=`<?xml version="1.0" encoding="utf-8"?><resources><color name="smart_mutawwif_background">${IVORY}</color></resources>`;
  put('android/res/values/smart_mutawwif_colors.xml',background);
  const androidSymbol=fs.readFileSync(path.join(root,'android/res/drawable/smart_mutawwif_symbol.xml'),'utf8');
  put('android/res/drawable/smart_mutawwif_monochrome.xml',androidSymbol.replaceAll(GREEN,'#FFFFFF').replaceAll(GOLD,'#FFFFFF'));
  put('android/res/drawable/smart_mutawwif_symbol_on_dark.xml',androidSymbol.replaceAll(GREEN,IVORY));
  fs.mkdirSync(path.join(root,'android/res/drawable-nodpi'),{recursive:true});
  fs.copyFileSync(path.join(root,'png/smart-mutawwif-logo.png'),path.join(root,'android/res/drawable-nodpi/smart_mutawwif_logo.png'));
  const adaptive=`<?xml version="1.0" encoding="utf-8"?><adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/smart_mutawwif_background"/><foreground android:drawable="@drawable/smart_mutawwif_symbol"/><monochrome android:drawable="@drawable/smart_mutawwif_monochrome"/></adaptive-icon>`;
  put('android/res/mipmap-anydpi-v26/ic_smart_mutawwif.xml',adaptive);
  put('android/res/mipmap-anydpi-v26/ic_smart_mutawwif_round.xml',adaptive);
  const legacy=svg(108,108,`<rect width="108" height="108" fill="${IVORY}"/>${paths}`);
  for(const [density,size] of [['mdpi',48],['hdpi',72],['xhdpi',96],['xxhdpi',144],['xxxhdpi',192]]){
    await render(legacy,`android/res/mipmap-${density}/ic_smart_mutawwif.png`,size);
    await render(svg(108,108,`<circle cx="54" cy="54" r="54" fill="${IVORY}"/>${paths}`),`android/res/mipmap-${density}/ic_smart_mutawwif_round.png`,size);
  }
  fs.mkdirSync(path.join(root,'web'),{recursive:true});
  for(const [src,dst] of [['png/smart-mutawwif-logo.png','web/smart-mutawwif-logo.png'],['png/smart-mutawwif-logo-on-dark.png','web/smart-mutawwif-logo-on-dark.png'],['png/symbol/smart-mutawwif-symbol-256.png','web/smart-mutawwif-symbol.png'],['png/symbol/smart-mutawwif-symbol-32.png','web/favicon-32.png'],['source/smart-mutawwif-symbol-compact.svg','web/smart-mutawwif-symbol.svg']]) fs.copyFileSync(path.join(root,src),path.join(root,dst));
  const qa={assetType:'Native SVG and XML implementation of the selected generated concept',title,slogan,checks:[],renderer:'sharp '+sharp.versions.sharp};
  for(const name of ['png/smart-mutawwif-logo.png','png/smart-mutawwif-logo-3200.png','png/symbol/smart-mutawwif-symbol-512.png','png/smart-mutawwif-adaptive-foreground.png']){
    const file=path.join(root,name);const meta=await sharp(file).metadata();const {data,info}=await sharp(file).ensureAlpha().raw().toBuffer({resolveWithObject:true});let transparent=0,opaque=0;for(let i=3;i<data.length;i+=4){transparent+=data[i]===0;opaque+=data[i]===255;}
    if(!meta.hasAlpha||transparent===0||opaque===0)throw new Error('Invalid alpha in '+name);
    qa.checks.push({file:name,width:meta.width,height:meta.height,hasAlpha:meta.hasAlpha,transparentPixels:transparent,opaquePixels:opaque});
  }
  put('qa.json',JSON.stringify(qa,null,2));console.log(JSON.stringify(qa));
}
main().catch(e=>{console.error(e);process.exitCode=1;});
