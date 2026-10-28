import {
  DiscordIcon,
  GithubIcon,
  RedditIcon,
  TwitterIcon,
  YouTubeIcon,
} from './icons';

export const relatedLinks = [
  {
    icon: <GithubIcon />,
    title: 'GitHub',
    link: 'https://github.com/diegonmarcos/cloudy-u-android',
  },
  {
    icon: <TwitterIcon />,
    title: 'X',
    link: 'https://twitter.com/CloudNotesOfficial',
  },
  {
    icon: <DiscordIcon />,
    title: 'Discord',
    link: BUILD_CONFIG.discordUrl,
  },
  {
    icon: <YouTubeIcon />,
    title: 'YouTube',
    link: 'https://www.youtube.com/@cloudnotespro',
  },
  {
    icon: <RedditIcon />,
    title: 'Reddit',
    link: 'https://www.reddit.com/r/cloud-notes/',
  },
];
