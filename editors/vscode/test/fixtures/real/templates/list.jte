@import app.Sword
@param swords: List<Sword>
@param nonce: String

<ul id="swords" data-signals:selected="''" data-nonce="${nonce}">
  @for(sword in swords)
    <li data-on:click="$selected = '${sword.id}'" data-class:chosen="$selected === '${sword.id}'">${sword.name}</li>
  @endfor
  <li data-on:click__debunce.200ms="@get('/swords/${swords.size}')">More</li>
  <li data-text="$selcted"></li>
</ul>
