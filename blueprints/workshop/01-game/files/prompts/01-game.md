# Step 1 — Build a small browser game in the web-app

Replace the default welcome card in the web-app with a playable browser
game. Single page, no backend, state in React hooks. This step warms
you up to the lab's dev loop — you'll add a Rust api in step 2.

## File to edit

`code/services/{{web_service}}/app/routes/home.tsx`

That's the only file you need to touch. The default content fetches
`GET /api/hello` from the api — you can delete that whole block.

## Already wired for you

- **Hot reload** — `bun run dev --host` is running in the cluster. Save
  the file, the browser refreshes.
- **Tailwind 4** — utility classes work out of the box.
- **shadcn/ui** — `Button`, `Card`, `Badge`, `Input` etc. are importable
  from `~/components/ui/*`.
- **TypeScript + React 19** — `useState`, `useReducer`, `useEffect`
  available. Keep state local to the component.

## Pick a game (any of these is fine)

- **Tic-tac-toe** — 3×3 grid, alternating X/O, "you won" message,
  reset button.
- **Memory match** — 4×4 grid of face-down cards, flip two at a time,
  match them up.
- **Snake** — keyboard arrow keys, growing snake on a grid, game over
  when you hit a wall or yourself.
- **Flappy square** — space bar to flap, gravity, dodge pipes.
- Or your own idea — keep it under ~150 lines.

## Where to view it

```
https://web-app--rs--development--default--main--<your-user-id>.dm-k8s.bluetext.dev/
```

Your user id is in the system `README.md`. The first browser visit
needs you to log in.

## Definition of done

- The game is playable in the browser.
- No errors in the browser dev console.
- `bun run` build is clean (no TS errors).

## When you're done

Tell me you're ready for step 2 and we'll add a Rust api service plus
an OpenRouter-backed chat agent.
